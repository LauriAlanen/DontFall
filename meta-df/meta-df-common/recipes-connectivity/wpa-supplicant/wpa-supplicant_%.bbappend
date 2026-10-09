# DontFall: optional WiFi auto-connect configured from local.conf.
#
#   DF_WIFI_SSID = "myssid"
#   DF_WIFI_PASSWORD = "mypassword"   # empty = open network
#
# When DF_WIFI_SSID is set this installs a wpa_supplicant@wlan0 config,
# enables the service and drops in a systemd-networkd match so wlan0 gets
# DHCP (the stock image only ships wired .network files - wlan0 would
# associate but never get an IPv4). With no SSID set the bbappend is inert.
#
# The passphrase is hashed to a PSK here at build time - plaintext never
# lands in the image or on the target.
python df_wifi_install() {
    ssid = d.getVar('DF_WIFI_SSID')
    if not ssid:
        return
    password = d.getVar('DF_WIFI_PASSWORD') or ''

    import hashlib, os
    sysconf = d.getVar('sysconfdir').lstrip('/')
    unitdir = d.getVar('systemd_system_unitdir').lstrip('/')

    net = 'network={\n    ssid="%s"\n' % ssid.replace('"', '\\"')
    if password:
        psk = hashlib.pbkdf2_hmac('sha1', password.encode(), ssid.encode(), 4096, 32)
        net += '    psk=%s\n' % psk.hex()
    else:
        net += '    key_mgmt=NONE\n'
    net += '}\n'

    confd = os.path.join(d.getVar('D'), sysconf, 'wpa_supplicant')
    os.makedirs(confd, exist_ok=True)
    conf = os.path.join(confd, 'wpa_supplicant-wlan0.conf')
    with open(conf, 'w') as f:
        f.write('ctrl_interface=/var/run/wpa_supplicant\n'
                'eapol_version=1\n'
                'ap_scan=1\n'
                'fast_reauth=1\n\n' + net)
    os.chmod(conf, 0o600)

    wants = os.path.join(d.getVar('D'), sysconf,
                         'systemd/system/multi-user.target.wants')
    os.makedirs(wants, exist_ok=True)
    os.symlink('/' + unitdir + '/wpa_supplicant@.service',
               os.path.join(wants, 'wpa_supplicant@wlan0.service'))

    # networkd: DHCP for any wlan station interface (mirrors systemd's
    # 80-wifi-station.network.example, which ships disabled in /lib)
    netd = os.path.join(d.getVar('D'), sysconf, 'systemd/network')
    os.makedirs(netd, exist_ok=True)
    with open(os.path.join(netd, '80-wifi-station.network'), 'w') as f:
        f.write('[Match]\n'
                'Type=wlan\n'
                'WLANInterfaceType=station\n'
                'SSID=*\n\n'
                '[Network]\n'
                'DHCP=yes\n')
}
do_install[postfuncs] += "df_wifi_install"

FILES:${PN} += "${sysconfdir}/wpa_supplicant/ \
                ${sysconfdir}/systemd/network/ \
                ${sysconfdir}/systemd/system/multi-user.target.wants/"
