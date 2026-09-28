"""Patches the decoded original APK: hooks TvBridge into MainActivity, adds Wi-Fi permissions."""
import sys
d = sys.argv[1]
p = d + '/smali_classes3/com/example/tvremote/MainActivity.smali'
s = open(p).read()
old = '''    iget-object v1, p0, Lcom/example/tvremote/MainActivity;->webView:Landroid/webkit/WebView;

    const-string v2, "file:///android_asset/remote.html"'''
assert s.count(old) == 1, "MainActivity.smali layout differs from expected"
s = s.replace(old, '''    iget-object v1, p0, Lcom/example/tvremote/MainActivity;->webView:Landroid/webkit/WebView;

    invoke-static {p0, v1}, Lcom/example/tvremote/TvBridge;->install(Landroid/app/Activity;Landroid/webkit/WebView;)V

    const-string v2, "file:///android_asset/remote.html"''')
s += '''
.method protected onDestroy()V
    .locals 0

    invoke-static {}, Lcom/example/tvremote/TvBridge;->shutdown()V

    invoke-super {p0}, Landroidx/appcompat/app/AppCompatActivity;->onDestroy()V

    return-void
.end method
'''
# --- Back navigation: gesture-back / back button must step back one layer at a time -------------
# Original onBackPressed() only asked WebView.canGoBack() (always false: the page never pushes history,
# all screens are CSS overlays) and then called super -> the whole app exited. Replace the body so it
# asks the page (window.__tv.handleBack) to close its top-most layer; only exit from the main page.
import re
_re = re.compile(r'\.method public onBackPressed\(\)V.*?\.end method', re.S)
assert len(_re.findall(s)) == 1, "onBackPressed not found in MainActivity.smali"
_new_back = '''.method public onBackPressed()V
    .locals 2

    iget-object v0, p0, Lcom/example/tvremote/MainActivity;->webView:Landroid/webkit/WebView;

    invoke-static {p0, v0}, Lcom/example/tvremote/TvBridge;->handleBack(Landroid/app/Activity;Landroid/webkit/WebView;)V

    return-void
.end method'''
s = _re.sub(lambda m: _new_back, s)
open(p, 'w').write(s)
m = open(d + '/AndroidManifest.xml').read()
import re
m = re.sub(r'(<manifest\b[^>]*\bpackage=\")([^\"]+)(\")', r'\1com.remote13.mirroring\3', m, count=1)
a = '<uses-permission android:name="android.permission.INTERNET"/>'
assert a in m
m = m.replace(a, a + '\n    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE"/>\n    <uses-permission android:name="android.permission.ACCESS_WIFI_STATE"/>\n    <uses-permission android:name="android.permission.CHANGE_WIFI_MULTICAST_STATE"/>\n    <uses-permission android:name="android.permission.RECORD_AUDIO"/>\n    <uses-permission android:name="android.permission.READ_MEDIA_IMAGES"/>\n    <uses-permission android:name="android.permission.READ_MEDIA_VIDEO"/>\n    <uses-permission android:name="android.permission.READ_MEDIA_AUDIO"/>\n    <uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" android:maxSdkVersion="32"/>\n    <uses-permission android:name="android.permission.BLUETOOTH"/>\n    <uses-permission android:name="android.permission.BLUETOOTH_ADMIN"/>\n    <uses-permission android:name="android.permission.BLUETOOTH_CONNECT"/>\n    <uses-permission android:name="android.permission.BLUETOOTH_ADVERTISE"/>')
if '</application>' in m and 'com.example.tvremote.CastPickerActivity' not in m:
    m = m.replace('</application>', '    <activity android:name="com.example.tvremote.CastPickerActivity" android:exported="false" />\n</application>')
open(d + '/AndroidManifest.xml', 'w').write(m)
