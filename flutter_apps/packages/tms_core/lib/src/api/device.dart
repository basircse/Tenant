import 'dart:io';
import 'dart:math';

import 'package:device_info_plus/device_info_plus.dart';
import 'package:flutter/foundation.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// Identifies this installation to the server.
///
/// The key is random, generated once and kept until the app is uninstalled or
/// its data is cleared. The server limits how many admin-app installations a
/// licence may use, so the key must be stable.
class DeviceIdentity {
  DeviceIdentity(this.key, this.name, this.platform);

  final String key;
  final String name;
  final String platform;

  static const _prefsKey = 'tms_device_key';

  static Future<DeviceIdentity> load(SharedPreferences prefs) async {
    var key = prefs.getString(_prefsKey);
    if (key == null || key.length < 16) {
      final r = Random.secure();
      key = List.generate(32, (_) => r.nextInt(16).toRadixString(16)).join();
      await prefs.setString(_prefsKey, key);
    }
    return DeviceIdentity(key, await _name(), _platform());
  }

  static String _platform() {
    if (kIsWeb) return 'web';
    return Platform.operatingSystem;
  }

  static Future<String> _name() async {
    try {
      final info = DeviceInfoPlugin();
      if (kIsWeb) return 'Web browser';
      if (Platform.isAndroid) {
        final a = await info.androidInfo;
        final brand = a.manufacturer.isEmpty
            ? ''
            : '${a.manufacturer[0].toUpperCase()}${a.manufacturer.substring(1)} ';
        return '$brand${a.model}'.trim();
      }
      if (Platform.isIOS) return (await info.iosInfo).name;
      if (Platform.isWindows) return (await info.windowsInfo).computerName;
      if (Platform.isMacOS) return (await info.macOsInfo).computerName;
      if (Platform.isLinux) return (await info.linuxInfo).prettyName;
    } catch (_) {
      // Fall through to a generic name.
    }
    return '${_platform()} device';
  }
}
