import 'dart:async';
import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:package_info_plus/package_info_plus.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../l10n/i18n.dart';
import '../models/models.dart';
import 'api_client.dart';
import 'device.dart';
import 'server_config.dart';
import 'updates.dart';

/// Which app is running. Each app only accepts its own kind of account.
enum AppKind { admin, tenant }

enum SessionState { starting, signedOut, signedIn }

/// Sign-in state shared by both apps: tokens, the current user and the
/// licence lock. Business data lives in each app's own store.
class Session extends ChangeNotifier {
  Session(this.app);

  final AppKind app;

  late SharedPreferences prefs;
  late DeviceIdentity device;
  late final ApiClient api;
  String appVersion = '';

  SessionState state = SessionState.starting;
  Me? me;

  /// Set when the server refuses service because of the licence (or a blocked
  /// device). The app shows a lock screen until it clears.
  ApiException? blocked;

  /// A message for the sign-in screen (e.g. "session ended").
  String? notice;

  /// Latest published version of this app, if the server announces one.
  AppRelease? release;
  bool updateDismissed = false;

  UpdateStatus get update => updateStatus(appVersion, release);

  /// Hooks run after sign-in / before sign-out (push registration etc.).
  final List<Future<void> Function()> onSignedIn = [];
  final List<Future<void> Function()> onSigningOut = [];

  String get _refreshKey => 'tms_refresh_${app.name}';

  bool get isSignedIn => state == SessionState.signedIn && me != null;

  /// The server's id for this device (the access token's `dev` claim).
  String? get deviceId {
    final token = api.accessToken;
    if (token == null) return null;
    try {
      final payload = token.split('.')[1];
      final json = jsonDecode(utf8.decode(base64Url.decode(base64Url.normalize(payload))));
      return (json as Map)['dev'] as String?;
    } catch (_) {
      return null;
    }
  }

  /// True during the grace period: the server accepts reads only.
  bool get readOnly => me?.licenseState == LicenseState.grace;

  Future<void> start() async {
    prefs = await SharedPreferences.getInstance();
    device = await DeviceIdentity.load(prefs);
    await ServerConfig.init();
    try {
      final info = await PackageInfo.fromPlatform();
      appVersion = info.version;
    } catch (_) {}
    api = ApiClient(baseUrl: ServerConfig.load(prefs))   
      ..onRefreshTokenChanged = _storeRefreshToken
      ..onBlocked = _handleBlocked
      ..onSessionExpired = _handleExpired
      ..onUserRefreshed = (j) {
        me = Me.fromApi(j);
        notifyListeners();
      };
    unawaited(checkForUpdate());
    final saved = prefs.getString(_refreshKey);
    if (saved != null) {
      api.refreshToken = saved;
      if (await api.refreshSession() && me != null) {
        await _afterSignIn();
        return;
      }
      if (api.refreshToken != null && me == null && blocked == null) {
        // Offline at start-up: try /me later when the user retries.
        state = SessionState.signedOut;
        notice = tr('Cannot reach the server. Check your connection and sign in again.');
        notifyListeners();
        return;
      }
      if (blocked != null && api.refreshToken != null) {
        // Licence lock: the refresh token is still valid; show the lock screen.
        await _loadMeQuietly();
        state = me == null ? SessionState.signedOut : SessionState.signedIn;
        notifyListeners();
        return;
      }
    }
    state = SessionState.signedOut;
    notifyListeners();
  }

  void dismissUpdate() {
    updateDismissed = true;
    notifyListeners();
  }

  Future<void> checkForUpdate() async {
    release = await AppRelease.fetch(api, app.name);
    notifyListeners();
  }

  Future<void> _loadMeQuietly() async {
    try {
      me = Me.fromApi(asJson(await api.get('/api/auth/me')));
    } on ApiException {
      // Leave as is.
    }
  }

  Future<void> setServer(String? url) async {
    await ServerConfig.save(prefs, url);
    api.baseUrl = ServerConfig.load(prefs);
    notifyListeners();
    unawaited(checkForUpdate());
  }

  String get serverUrl => api.baseUrl;

  /// Signs in. Throws [ApiException] with a user-friendly message.
  Future<void> login(String identifier, String password) async {
    final body = await api.post('/api/auth/login', {
      'identifier': latinDigits(identifier.trim()),
      'password': password,
      'deviceKey': device.key,
      'deviceName': device.name,
      'platform': device.platform,
    });
    final user = Me.fromApi(asJson(body['user']));
    final wrongApp = switch (app) {
      AppKind.admin => user.isTenant,
      AppKind.tenant => !user.isTenant,
    };
    api.applyTokens(asJson(body));
    if (wrongApp) {
      await _revoke();
      throw ApiException(
          403,
          app == AppKind.admin
              ? tr('This is a tenant account. Please use the Tenant app.')
              : tr('This is a staff account. Please use the Admin app.'));
    }
    me = user;
    blocked = null;
    notice = null;
    await _afterSignIn();
  }

  Future<void> _afterSignIn() async {
    final user = me!;
    if (!user.licenseState.usable && !user.isVendor) {
      blocked = ApiException(403, _stateMessage(user.licenseState), code: _stateCode(user.licenseState));
    }
    state = SessionState.signedIn;
    notifyListeners();
    for (final hook in onSignedIn) {
      try {
        await hook();
      } catch (e) {
        debugPrint('sign-in hook failed: $e');
      }
    }
  }

  Future<void> logout() async {
    for (final hook in onSigningOut) {
      try {
        await hook();
      } catch (_) {}
    }
    await _revoke();
    me = null;
    blocked = null;
    state = SessionState.signedOut;
    notifyListeners();
  }

  Future<void> _revoke() async {
    final token = api.refreshToken;
    if (token != null) {
      try {
        await api.post('/api/auth/logout', {'refreshToken': token});
      } catch (_) {
        // Signing out locally is what matters.
      }
    }
    api.clearTokens();
  }

  Future<void> reloadMe() async {
    me = Me.fromApi(asJson(await api.get('/api/auth/me')));
    notifyListeners();
  }

  Future<void> changePassword(String current, String next) async {
    await api.post('/api/auth/change-password', {'currentPassword': current, 'newPassword': next});
    await reloadMe();
  }

  Future<void> forgotPassword(String identifier) =>
      api.post('/api/auth/forgot-password', {'identifier': latinDigits(identifier.trim())});

  /// Registers a new landlord organisation (admin app). Returns the server's message.
  Future<String> signup({
    required String organizationName,
    required String name,
    required String username,
    required String password,
    required String mobile,
    String email = '',
    String address = '',
  }) async {
    final body = await api.post('/api/auth/signup', {
      'organizationName': organizationName.trim(),
      'name': name.trim(),
      'username': username.trim(),
      'password': password,
      'mobile': mobile.trim(),
      'email': email.trim(),
      'address': address.trim(),
    });
    return str(body?['message']);
  }

  /// Asks the server whether the lock has been lifted (e.g. after approval).
  Future<LicenseStatus?> recheckLicense() async {
    try {
      final status = LicenseStatus.fromApi(asJson(await api.get('/api/license')));
      if (status.state.usable) {
        blocked = null;
        await reloadMe();
      }
      notifyListeners();
      return status;
    } on ApiException catch (e) {
      if (e.code == LicenseCodes.deviceBlocked) blocked = e;
      notifyListeners();
      rethrow;
    }
  }

  void _storeRefreshToken(String? token) {
    if (token == null) {
      prefs.remove(_refreshKey);
    } else {
      prefs.setString(_refreshKey, token);
    }
  }

  void _handleBlocked(ApiException e) {
    if (blocked?.code == e.code) return;
    blocked = e;
    notifyListeners();
  }

  void _handleExpired(String message) {
    if (state == SessionState.signedOut) return;
    api.clearTokens();
    me = null;
    blocked = null;
    notice = message;
    state = SessionState.signedOut;
    notifyListeners();
  }

  static String _stateCode(LicenseState s) => switch (s) {
        LicenseState.pending => LicenseCodes.pending,
        LicenseState.rejected => LicenseCodes.rejected,
        LicenseState.suspended => LicenseCodes.suspended,
        _ => LicenseCodes.expired,
      };

  static String _stateMessage(LicenseState s) => switch (s) {
        LicenseState.pending => tr('Your registration is waiting for approval.'),
        LicenseState.rejected => tr('Your registration was not approved.'),
        LicenseState.suspended => tr('This account has been suspended.'),
        _ => tr('The subscription has expired.'),
      };
}
