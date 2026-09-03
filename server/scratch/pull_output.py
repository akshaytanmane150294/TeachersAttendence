import os
import sys
import subprocess

def find_adb():
    local_app_data = os.environ.get('LOCALAPPDATA', '')
    sdk_adb = os.path.join(local_app_data, 'Android', 'Sdk', 'platform-tools', 'adb.exe')
    if os.path.exists(sdk_adb):
        return sdk_adb
    return 'adb'

def get_connected_device(adb):
    try:
        out = subprocess.check_output([adb, 'devices']).decode('utf-8')
        lines = [line.strip() for line in out.split('\n') if line.strip() and not line.startswith('List of')]
        for line in lines:
            if line.endswith('\tdevice') or line.endswith(' device'):
                if '\tdevice' in line:
                    return line.rsplit('\tdevice', 1)[0].strip()
                else:
                    return line.rsplit(' device', 1)[0].strip()
    except Exception:
        pass
    return None

def main():
    print("=" * 80)
    print("  [*] School Attendance - Pull Debug Images to Laptop Output Folder")
    print("=" * 80)
    
    adb = find_adb()
    device = get_connected_device(adb)
    
    if not device:
        print("\n[!] ERROR: No Android device detected!")
        return 1

    laptop_output_dir = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..', 'output'))
    if not os.path.exists(laptop_output_dir):
        os.makedirs(laptop_output_dir, exist_ok=True)

    print(f"Connected Device: {device}")
    print(f"Pulling ONLY latest debug image: /sdcard/Download/SchoolAttendance/output/debug_latest.png")
    print(f"Destination: {os.path.join(laptop_output_dir, 'debug_latest.png')}")
    
    try:
        # Pull ONLY the single latest image file
        target_file = os.path.join(laptop_output_dir, 'debug_latest.png')
        subprocess.run([
            adb, '-s', device, 'pull',
            '/sdcard/Download/SchoolAttendance/output/debug_latest.png',
            target_file
        ], check=False)
        print("\n[OK] Single latest debug image pulled successfully into 'output/debug_latest.png'!")
    except Exception as e:
        print(f"[!] Error pulling debug image: {e}")
        return 1

    return 0

if __name__ == '__main__':
    sys.exit(main())
