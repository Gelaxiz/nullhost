#!/usr/bin/env python3
"""
Nullhost Mobile Packaging Suite
Automates compilation and packaging of:
1. Symbian ^3 / Belle FP2 Standalone Native SISX (Nullhost_Belle.sisx)
2. Series 60 Keypad Standalone Native SIS (Nullhost_S60.sis)
3. Universal Java ME MIDlet (Nullhost.jar + Nullhost.jad)
"""

import os
import sys
import subprocess
import shutil
import zipfile

BASE_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), '..'))
CLIENT_DIR = os.path.join(BASE_DIR, 'client')
TOOLS_DIR = os.path.join(BASE_DIR, 'tools')
ENSYMBLE_PY = os.path.join(TOOLS_DIR, 'ensymble-ng', 'ensymble.py')
JAVAC_BIN = os.path.join(TOOLS_DIR, 'jdk8', 'bin', 'javac')
MICROEMU_JAR = os.path.join(TOOLS_DIR, 'microemulator-2.0.4.jar')
MICROEMU_JSR75_JAR = os.path.join(TOOLS_DIR, 'microemu-jsr-75.jar')

def build_symbian_belle():
    print("[1/3] Building Symbian Belle FP2 Standalone Native App...")
    src_file = os.path.join(CLIENT_DIR, 'symbian_belle', 'nullhost_belle.py')
    out_target = os.path.join(CLIENT_DIR, 'Nullhost_Belle.sisx')
    
    cmd = [
        sys.executable, ENSYMBLE_PY, 'py2sis',
        '--runtime=pys60-2.0',
        '--appname=Nullhost',
        '--caption=Nullhost',
        '--shortcaption=Nullhost',
        '--version=1.0.0',
        '--vendor=Gelaxiz',
        '--uid=0xe009f810',
        '--caps=LocalServices+NetworkServices+ReadUserData+WriteUserData+UserEnvironment',
        src_file,
        out_target
    ]
    subprocess.check_call(cmd)
    
    # ensymble produces .sis extension if not present, copy to .sisx
    produced = out_target + '.sis'
    if os.path.exists(produced):
        shutil.copy2(produced, out_target)
    print(f"  -> Generated: {out_target} ({os.path.getsize(out_target)} bytes)")

def build_s60_keypad():
    print("[2/3] Building Series 60 (S60 Keypad) Standalone Native App...")
    src_file = os.path.join(CLIENT_DIR, 's60_keypad', 'nullhost_s60.py')
    out_target = os.path.join(CLIENT_DIR, 'Nullhost_S60.sis')
    
    cmd = [
        sys.executable, ENSYMBLE_PY, 'py2sis',
        '--runtime=pys60-1.4',
        '--appname=NullhostS60',
        '--caption=Nullhost',
        '--shortcaption=Nullhost',
        '--version=1.0.0',
        '--vendor=Gelaxiz',
        '--uid=0xe009f811',
        '--caps=LocalServices+NetworkServices+ReadUserData+WriteUserData+UserEnvironment',
        src_file,
        out_target
    ]
    subprocess.check_call(cmd)
    
    produced = out_target if os.path.exists(out_target) else out_target + '.sis'
    if os.path.exists(produced) and produced != out_target:
        shutil.copy2(produced, out_target)
    print(f"  -> Generated: {out_target} ({os.path.getsize(out_target)} bytes)")

def build_j2me_universal():
    print("[3/3] Compiling and packaging Universal Java ME MIDlet...")
    j2me_dir = os.path.join(CLIENT_DIR, 'j2me')
    src_dir = os.path.join(j2me_dir, 'src')
    build_dir = os.path.join(j2me_dir, 'build')
    os.makedirs(build_dir, exist_ok=True)
    
    # 1. Compile Java sources
    javac = JAVAC_BIN if os.path.exists(JAVAC_BIN) else 'javac'
    cp = f"{MICROEMU_JAR}:{MICROEMU_JSR75_JAR}"
    java_files = [os.path.join(src_dir, f) for f in os.listdir(src_dir) if f.endswith('.java')]
    
    cmd = [
        javac, '-source', '1.6', '-target', '1.6',
        '-cp', cp,
        '-d', build_dir
    ] + java_files
    subprocess.check_call(cmd)
    
    # 2. Package JAR with Manifest
    manifest = """Manifest-Version: 1.0
MIDlet-1: Nullhost, , org.gelaxiz.nullhost.NullhostMIDlet
MIDlet-Name: Nullhost
MIDlet-Version: 1.0.0
MIDlet-Vendor: Gelaxiz
MIDlet-Description: Fast, anonymous, ephemeral file hosting for Java ME
MicroEdition-Configuration: CLDC-1.1
MicroEdition-Profile: MIDP-2.0
MIDlet-Permissions: javax.microedition.io.Connector.http, javax.microedition.io.Connector.file.read
"""
    jar_target = os.path.join(CLIENT_DIR, 'Nullhost.jar')
    with zipfile.ZipFile(jar_target, 'w', zipfile.ZIP_DEFLATED) as zf:
        zf.writestr('META-INF/MANIFEST.MF', manifest)
        for root, dirs, files in os.walk(build_dir):
            for f in files:
                full = os.path.join(root, f)
                arcname = os.path.relpath(full, build_dir)
                zf.write(full, arcname)
                
    jar_size = os.path.getsize(jar_target)
    
    # 3. Write JAD file
    jad_target = os.path.join(CLIENT_DIR, 'Nullhost.jad')
    jad_content = f"""MIDlet-1: Nullhost, , org.gelaxiz.nullhost.NullhostMIDlet
MIDlet-Name: Nullhost
MIDlet-Version: 1.0.0
MIDlet-Vendor: Gelaxiz
MIDlet-Description: Fast, anonymous, ephemeral file hosting for Java ME
MicroEdition-Configuration: CLDC-1.1
MicroEdition-Profile: MIDP-2.0
MIDlet-Jar-URL: Nullhost.jar
MIDlet-Jar-Size: {jar_size}
MIDlet-Permissions: javax.microedition.io.Connector.http, javax.microedition.io.Connector.file.read
"""
    with open(jad_target, 'w') as f:
        f.write(jad_content)
        
    print(f"  -> Generated: {jar_target} ({jar_size} bytes)")
    print(f"  -> Generated: {jad_target}")

def main():
    print("=== Nullhost Multi-Platform Packaging Pipeline ===")
    os.makedirs(CLIENT_DIR, exist_ok=True)
    build_symbian_belle()
    build_s60_keypad()
    build_j2me_universal()
    print("=== All mobile applications packaged successfully! ===")

if __name__ == '__main__':
    main()
