import 'dart:io';

import 'package:device_info_plus/device_info_plus.dart';
import 'package:flutter/foundation.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// Where the TMS server lives.
///
/// Release builds use the URL given at build time:
/// `flutter build apk --dart-define=TMS_API_URL=https://api.example.com`.
/// Debug builds (and builds made with `--dart-define=TMS_SERVER_SETTINGS=true`)
/// also let the user change it from the sign-in screen, which is handy for
/// testing against a computer on the local network.
class ServerConfig {
  static const _prefsKey = 'tms_server_url';
  static const _builtIn = String.fromEnvironment('TMS_API_URL');
  static const _allowOverride = bool.fromEnvironment('TMS_SERVER_SETTINGS');

  static bool get canChange => kDebugMode || _allowOverride || _builtIn.isEmpty;

  /// Set by [init]: true on an Android emulator, false on a real phone.
  static bool _androidEmulator = true;

  /// Finds out whether this is an Android emulator or a real phone; call once at start-up.
  static Future<void> init() async {
    if (kIsWeb || !Platform.isAndroid) return;
    try {
      _androidEmulator = !(await DeviceInfoPlugin().androidInfo).isPhysicalDevice;
    } catch (_) {
      // Keep the emulator default.
    }
  }

  static String get defaultUrl {
    if (_builtIn.isNotEmpty) return _builtIn;
    // The Android emulator reaches the host computer at 10.0.2.2. A phone on USB reaches it at
    // localhost through `adb reverse tcp:8980 tcp:8980`, which debug builds set up automatically
    // (android/app/build.gradle.kts).
    if (!kIsWeb && Platform.isAndroid && _androidEmulator) return 'http://10.0.2.2:8980';
    return 'http://localhost:8980';
  }

  static String load(SharedPreferences prefs) {
    if (!canChange) return defaultUrl;
    return prefs.getString(_prefsKey) ?? defaultUrl;
  }

  static Future<void> save(SharedPreferences prefs, String? url) async {
    if (url == null || url.trim().isEmpty) {
      await prefs.remove(_prefsKey);
    } else {
      await prefs.setString(_prefsKey, normalize(url));
    }
  }

  static String normalize(String url) {
    var u = url.trim();
    if (!u.startsWith('http://') && !u.startsWith('https://')) u = 'http://$u';
    while (u.endsWith('/')) {
      u = u.substring(0, u.length - 1);
    }
    return u;
  }
}
