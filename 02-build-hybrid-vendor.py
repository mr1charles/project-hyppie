#!/usr/bin/env python3
"""Project Hyppie stage 2: assemble a 64-bit hybrid vendor tree for the Galaxy A13.

Base   : LineageOS 23.2 Galaxy A21s vendor (Exynos 850, 64-bit, Android 16)
Overlay: A13 config files from the A13 device tree (PRODUCT_COPY_FILES)
         A13 proprietary blobs listed in proprietary-files.txt
Output : work/hybrid/vendor  (+ hybrid/report.txt)
"""
import os, re, shutil, subprocess, sys
RELEASE = os.environ.get('HYPPIE_RELEASE') == '1'   # public package: no forced ADB

P = os.path.expanduser('~/ProjectHyppie')
DONOR_IMG = f'{P}/donor-lineage-a21s/img/vendor.img'
DEV_COMMON = f'{P}/a13src/device-common'
DEV = f'{P}/a13src/device'
BLOBS = f'{P}/a13src/vendor-blobs/proprietary'
STOCK = f'{P}/stock/tree'          # extracted A13 stock vendor/odm (fallback source)
OUT = f'{P}/work/hybrid'
VENDOR = f'{OUT}/vendor'

report = []
def log(msg):
    report.append(msg)
    print(msg)

def copy(src, dst):
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    shutil.copy2(src, dst)

# 1. fresh copy of the donor vendor, keeping file modes
if os.path.exists(OUT):
    shutil.rmtree(OUT)
os.makedirs(VENDOR)
subprocess.run(['fsck.erofs', f'--extract={VENDOR}', '--no-preserve-owner', DONOR_IMG],
               check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
log(f'base: donor vendor extracted to {VENDOR}')

# 2. A13 config files installed by the device tree
copy_re = re.compile(r'\$\((?:COMMON_PATH|DEVICE_PATH|LOCAL_PATH)\)/(\S+?):\$\(TARGET_COPY_OUT_VENDOR\)/(\S+)')
n = 0
for tree in (DEV_COMMON, DEV):
    for root, _, files in os.walk(tree):
        for f in files:
            if not f.endswith('.mk'):
                continue
            text = open(os.path.join(root, f), errors='ignore').read()
            for src_rel, dst_rel in copy_re.findall(text):
                src = os.path.join(tree, src_rel)
                if os.path.isfile(src):
                    copy(src, os.path.join(VENDOR, dst_rel))
                    n += 1
                else:
                    log(f'  config missing in tree: {src_rel}')
log(f'configs: copied {n} A13 config files from device trees')

# 3. A13 proprietary blobs
def parse_list(path):
    out = []
    for line in open(path, errors='ignore'):
        line = line.strip()
        if not line or line.startswith('#'):
            continue
        line = line.lstrip('-').split('|')[0].split(';')[0]
        src, _, dst = line.partition(':')
        out.append((src, dst or src))
    return out

copied = missing = 0
for lst in (f'{DEV_COMMON}/proprietary-files.txt', f'{DEV}/proprietary-files.txt'):
    if not os.path.isfile(lst):
        continue
    for src, dst in parse_list(lst):
        if not dst.startswith('vendor/'):
            continue
        cands = [os.path.join(BLOBS, dst), os.path.join(BLOBS, src),
                 os.path.join(STOCK, dst), os.path.join(STOCK, src)]
        found = next((c for c in cands if os.path.isfile(c)), None)
        if found:
            copy(found, os.path.join(OUT, dst))
            copied += 1
        else:
            missing += 1
            log(f'  blob not found: {dst}')
log(f'blobs: copied {copied}, missing {missing}')

# 4. identity props: present the vendor as an A13
bp = f'{VENDOR}/build.prop'
props = open(bp).read()
for k, v in {'ro.product.vendor.device': 'a13', 'ro.product.vendor.model': 'SM-A135M',
             'ro.product.vendor.name': 'a13ub',
             'ro.sf.lcd_density': '450'}.items():   # A13 stock density (A21s donor is 280)
    props, cnt = re.subn(rf'^{re.escape(k)}=.*$', f'{k}={v}', props, flags=re.M)
    if not cnt:
        props += f'\n{k}={v}\n'
extra = f'{DEV_COMMON}/vendor.prop'
if os.path.isfile(extra):
    props += '\n# --- A13 vendor.prop ---\n' + open(extra).read()
# unique id per build: the boot guard compiles all apps once on the first boot of each new build
import time
props += f'\nro.vendor.hyppie.build={time.strftime("%Y%m%d.%H%M%S")}\n'
open(bp, 'w').write(props)
log('props: identity set to a13 / SM-A135M, A13 vendor.prop appended, ro.hyppie.build stamped')

# 5. partitions the GSI/Lineage system may not ship become optional in fstab
fstab = f'{VENDOR}/etc/fstab.exynos850'
lines = []
for line in open(fstab):
    f = line.split()
    if len(f) >= 5 and f[0] in ('product', 'system_ext') and 'nofail' not in f[4]:
        line = line.replace(f[4], f[4] + ',nofail', 1)
    lines.append(line)
open(fstab, 'w').writelines(lines)
log('fstab: product/system_ext marked nofail')


# 5b. A13 stock 32-bit Samsung HIDL services replace A21s 64-bit ones that do not know A13 hardware
#     (camera: "Unknown sensorId 72"; fingerprint: A21s rear sensor vs A13 side sensor).
#     Each service's vendor library closure is copied from the A13 stock vendor (missing libs only).
SV = f'{STOCK}/vendor'
def _needed(f):
    out = subprocess.run(['readelf', '-d', f], capture_output=True, text=True).stdout
    return [l.split('[')[1].split(']')[0] for l in out.splitlines() if 'NEEDED' in l]
def _find(base, lib):
    for d in ('lib', 'lib/hw', 'lib/egl'):
        if os.path.isfile(f'{base}/{d}/{lib}'):
            return f'{d}/{lib}'
def use_stock_service(name, binary, rc, manifest, drop, roots=(), label=None):
    for f in drop:
        if os.path.exists(f'{VENDOR}/{f}'):
            os.remove(f'{VENDOR}/{f}')
    for f in (binary, rc):
        copy(f'{SV}/{f}', f'{VENDOR}/{f}')
    copy(f'{SV}/{manifest}', f'{VENDOR}/etc/vintf/manifest/hyppie_a13_{name}.xml')
    seen, todo, added = set(), [f'{SV}/{binary}'] + [f'{SV}/{r}' for r in roots], 0
    while todo:
        for lib in _needed(todo.pop()):
            if lib in seen:
                continue
            seen.add(lib)
            rel = _find(SV, lib)
            if rel is None:
                continue                      # system/LLNDK library
            if _find(VENDOR, lib) is None:
                copy(f'{SV}/{rel}', f'{VENDOR}/{rel}')
                added += 1
            todo.append(f'{SV}/{rel}')
    if label:
        esc = re.escape('/vendor/' + binary)
        with open(f'{VENDOR}/etc/selinux/vendor_file_contexts', 'a') as fc:
            fc.write(f'\n{esc}    u:object_r:{label}:s0\n')
    log(f'{name}: A13 stock 32-bit service {os.path.basename(binary)} (+{added} libs)')

use_stock_service('camera', 'bin/hw/vendor.samsung.hardware.camera.provider@4.0-service',
    'etc/init/vendor.samsung.hardware.camera.provider@4.0-service.rc',
    'etc/vintf/manifest/deviceManifest.xml',
    ['etc/init/android.hardware.camera.provider-service.samsung.rc',
     'etc/vintf/manifest/android.hardware.camera.provider-service.samsung.xml'],
    roots=['lib/hw/camera.exynos850.so'], label='hal_camera_default_exec')
copy(f'{P}/overlay-camera/vendor/lib/libexif.so', f'{VENDOR}/lib/libexif.so')   # was VNDK on Android 14
# libexynoscamera3 (A13 Lineage blob) needs libshim_camera.so (GraphicBufferMapper::unlock(handle)
# shim, LineageOS device-common/shims/libcamera); without it camera.exynos850.so never loads and the
# provider segfaults on a null CameraModule. Built on the PC by shims/libshim_camera.
copy(f'{P}/overlay-camera/vendor/lib/libshim_camera.so', f'{VENDOR}/lib/libshim_camera.so')
# fingerprint: libsynaFpSensorTestNwd.so (A13 Lineage blob, loaded by fingerprint.default.so) needs
# libshim_idiv0.so (ARM EABI __aeabi_idiv/uidiv/uidivmod + __srget, LineageOS device-common/shims/idiv0_shim);
# without it the fingerprint HAL module never opens. Built on the PC by shims/libshim_idiv0.
if os.environ.get('HYPPIE_FPSHIM') == '1':   # 2026-10-04: boot went to recovery with it -> opt-in until understood
    copy(f'{P}/overlay-camera/vendor/lib/libshim_idiv0.so', f'{VENDOR}/lib/libshim_idiv0.so')
use_stock_service('fingerprint', 'bin/hw/vendor.samsung.hardware.biometrics.fingerprint@3.0-service',
    'etc/init/vendor.samsung.hardware.biometrics.fingerprint@3.0-service.rc',
    'etc/vintf/manifest/vendor.samsung.hardware.biometrics.fingerprint@3.0-service.xml',
    ['etc/init/android.hardware.biometrics.fingerprint-service.samsung.rc',
     'etc/vintf/manifest/android.hardware.biometrics.fingerprint-service.samsung.xml'],
    roots=[r for r in ('lib/hw/fingerprint.default.so',) if os.path.isfile(f'{SV}/{r}')],
    label='hal_fingerprint_default_exec')

# 5c. sensors: the A13 hals.conf lists 32-bit sub-HALs (/vendor/lib/sensors.*.so) but the running sensor
# service is the A21s 64-bit multihal, which then loads nothing ("No Sensors on the device": no auto-rotate,
# step counter, compass). Point it at the A21s 64-bit sub-HALs, which drive the same Samsung sensor hub.
# (The A13's touchscreen "virtual proximity" sub-HAL exists only as 32-bit and can't be loaded here.)
hals = f'{VENDOR}/etc/sensors/hals.conf'
os.makedirs(os.path.dirname(hals), exist_ok=True)
if os.environ.get('HYPPIE_SENSORS') == 'a13':
    # A13 stock 32-bit HIDL multihal (allowed at FCM level 6): also loads sensors.inputvirtual.so, the A13's
    # touchscreen-based virtual proximity sensor, which only exists as 32-bit.
    use_stock_service('sensors', 'bin/hw/android.hardware.sensors@2.0-service.multihal',
        'etc/init/android.hardware.sensors@2.0-service-multihal.rc',
        'etc/vintf/manifest/android.hardware.sensors@2.0-multihal.xml',
        ['etc/init/android.hardware.sensors-service.samsung-multihal.rc',
         'etc/vintf/manifest/android.hardware.sensors-samsung-multihal.xml'],
        roots=['lib/sensors.sensorhub.so', 'lib/sensors.grip.so', 'lib/sensors.inputvirtual.so'],
        label='hal_sensors_default_exec')
    for lib in ('sensors.sensorhub.so', 'sensors.grip.so', 'sensors.inputvirtual.so'):
        if not os.path.isfile(f'{VENDOR}/lib/{lib}'):
            copy(f'{SV}/lib/{lib}', f'{VENDOR}/lib/{lib}')
    open(hals, 'w').write('/vendor/lib/sensors.sensorhub.so\n/vendor/lib/sensors.inputvirtual.so\n/vendor/lib/sensors.grip.so\n')
    log('sensors: A13 32-bit HIDL multihal with sensorhub + inputvirtual (proximity) + grip')
else:
    open(hals, 'w').write('/vendor/lib64/sensors.grip.so\n/vendor/lib64/sensors.sensorhub.so\n')
    for lib in ('sensors.grip.so', 'sensors.sensorhub.so'):
        assert os.path.isfile(f'{VENDOR}/lib64/{lib}'), f'missing 64-bit sensor sub-HAL {lib}'
    log('sensors: hals.conf -> 64-bit sensorhub + grip sub-HALs')

# 6. Project Hyppie overlay: boot guard, ADB always on (+ Wi-Fi), its SELinux policy
OV = f'{P}/overlay'
for root, _, files in os.walk(f'{OV}/vendor'):
    for f in files:
        src = os.path.join(root, f)
        copy(src, os.path.join(VENDOR, os.path.relpath(src, f'{OV}/vendor')))
with open(f'{VENDOR}/etc/selinux/vendor_sepolicy.cil', 'a') as cil:
    cil.write('\n' + open(f'{OV}/sepolicy/hyppie.cil').read())
with open(f'{VENDOR}/etc/selinux/vendor_file_contexts', 'a') as fc:
    fc.write('\n' + open(f'{OV}/sepolicy/file_contexts').read())
with open(bp, 'a') as b:
    if RELEASE:
        b.write('\n# Project Hyppie release build: normal ADB (off, with authorization)\n')
    else:
        b.write('\n' + open(f'{OV}/vendor.prop').read() + '\nro.vendor.hyppie.devadb=1\n')
for pre in ('precompiled_sepolicy', 'precompiled_sepolicy.plat_sepolicy_and_mapping.sha256'):
    pth = f'{VENDOR}/etc/selinux/{pre}'
    if os.path.exists(pth):
        os.remove(pth)   # policy changed: let init compile it at boot
log('overlay: boot guard, ADB props and hyppie.cil applied')

# 7. HyperOS target (HYPPIE_TARGET=hyperos): match its older Android 16 policy, mount
#    mi_ext, and force ADB on for test builds
if os.environ.get('HYPPIE_TARGET') == 'hyperos':
    cilp = f'{VENDOR}/etc/selinux/vendor_sepolicy.cil'
    cil = [l for l in open(cilp) if not re.search(r'\bnlmsg\b', l)]
    open(cilp, 'w').writelines(cil)
    with open(fstab, 'a') as fs:
        fs.write('mi_ext    /mnt/vendor/mi_ext    erofs    ro    wait,logical,first_stage_mount,nofail\n')
    if not RELEASE:
        with open(bp, 'a') as b:
            b.write('\n# Project Hyppie HyperOS test build: ADB always on, no authorization\n'
                    'ro.adb.secure=0\nro.debuggable=1\n')
    OH = f'{P}/overlay-hyperos'
    for root, _, files in os.walk(f'{OH}/vendor'):
        for f in files:
            src = os.path.join(root, f)
            copy(src, os.path.join(VENDOR, os.path.relpath(src, f'{OH}/vendor')))
    with open(cilp, 'a') as c:
        c.write('\n' + open(f'{OH}/sepolicy/hyperos.cil').read())
        c.write('\n' + open(f'{OH}/sepolicy/hyperos-denials.cil').read())   # needed once enforcing
    # Xiaomi libmedia opens the Qualcomm-style name and aborts zygote if it is missing
    shutil.copy2(f'{VENDOR}/etc/media_profiles_V1_0.xml', f'{VENDOR}/etc/media_profiles_vendor.xml')
    with open(bp, 'a') as b:
        b.write('media.settings.xml=/vendor/etc/media_profiles_vendor.xml\n')
    with open(f'{VENDOR}/etc/selinux/vendor_file_contexts', 'a') as fc:
        fc.write('\n' + open(f'{OH}/sepolicy/file_contexts').read())
    # HyperOS is built for a 64-bit-only device (Xiaomi 14): its multiArch apps (LyraSdkApp ->
    # continuity_sdk_app -> TeleService/SystemUI) are rejected if the device also advertises
    # 32-bit app ABIs. Run apps 64-bit only like the Xiaomi 14 (32-bit native HALs still work).
    bp_txt = open(bp).read()
    for k, v in {'ro.vendor.product.cpu.abilist': 'arm64-v8a', 'ro.vendor.product.cpu.abilist32': '',
                 'ro.vendor.product.cpu.abilist64': 'arm64-v8a', 'ro.zygote': 'zygote64'}.items():
        bp_txt, n = re.subn(rf'^{re.escape(k)}=.*$', f'{k}={v}', bp_txt, flags=re.M)
        if not n:
            bp_txt += f'\n{k}={v}\n'
    open(bp, 'w').write(bp_txt)
    log('hyperos: apps 64-bit only like the Xiaomi 14 (abilist arm64-v8a, zygote64)')
    with open(bp, 'a') as b:
        b.write('\n' + open(f'{OH}/vendor.prop').read())
    log('hyperos: performance props applied (HWUI OpenGL)')
    # A13 framework overlay: teardrop cutout (Super Island / status bar), rounded corners,
    # HIDL side-fingerprint sensor registration (config_biometric_sensors 0:2:15)
    # 2026-10-04: with this overlay installed system_server looped on "Missing permission definition
    # for android.permission.RANGING" (app op 151) -> opt-in only until the overlay is reworked
    # cutout-only overlay (teardrop notch + corners for the status bar / Super Island), no biometrics
    # 2026-10-04: even the cutout-only overlay kept boot from completing -> any self-built RRO on
    # 'android' breaks HyperOS here; opt-in only (HYPPIE_CUTOUT=1) until the cause is found
    if os.environ.get('HYPPIE_CUTOUT') == '1':
        copy(f'{P}/overlay-rro/cutout/build/signed.apk', f'{VENDOR}/overlay/HyppieA13Cutout.apk')
        log('hyperos: A13 cutout overlay (teardrop notch, rounded corners) added')
    if os.environ.get('HYPPIE_RRO') == '1':
        copy(f'{P}/overlay-rro/framework/build/signed.apk', f'{VENDOR}/overlay/HyppieA13Framework.apk')
        log('hyperos: A13 framework overlay (cutout, corners, fingerprint) added')
    log('hyperos: nlmsg rules dropped, mi_ext mount added, '
        + ('normal ADB (release), ' if RELEASE else 'ro.adb.secure=0 ro.debuggable=1, ')
        + ''
        'patched libs + bind mounts applied')

# 8. quick facts for review
props = open(bp).read()
z = re.search(r'^ro\.zygote=(.*)$', props, re.M)
log(f'ro.zygote = {z.group(1) if z else "unset"}')
log(f'lib64 files: {sum(len(f) for _, _, f in os.walk(VENDOR + "/lib64"))}')
open(f'{OUT}/report.txt', 'w').write('\n'.join(report) + '\n')
