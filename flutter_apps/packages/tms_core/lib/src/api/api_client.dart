import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:http/http.dart' as http;

import '../l10n/i18n.dart';

/// Error codes the server attaches to licence refusals (HTTP 403 `code`).
class LicenseCodes {
  static const pending = 'LICENSE_PENDING';
  static const rejected = 'LICENSE_REJECTED';
  static const suspended = 'LICENSE_SUSPENDED';
  static const expired = 'LICENSE_EXPIRED';
  static const readOnly = 'LICENSE_READ_ONLY';
  static const devicePending = 'DEVICE_PENDING';
  static const deviceBlocked = 'DEVICE_BLOCKED';
  static const unitLimit = 'UNIT_LIMIT';

  /// The user was blocked, deactivated or locked: the session ends with the server's message.
  static const accountBlocked = 'ACCOUNT_BLOCKED';
  static const accountInactive = 'ACCOUNT_INACTIVE';
  static const endSession = {accountBlocked, accountInactive};

  /// Codes that lock the whole app (as opposed to refusing one action).
  static const blocking = {pending, rejected, suspended, expired, deviceBlocked};
}

/// A failed API call. [message] is suitable for showing to the user.
class ApiException implements Exception {
  ApiException(this.status, this.message, {this.code, this.fieldErrors = const {}});

  /// HTTP status; 0 when the server could not be reached.
  final int status;
  final String message;
  final String? code;
  final Map<String, String> fieldErrors;

  bool get isNetwork => status == 0;
  bool get blocksApp => code != null && LicenseCodes.blocking.contains(code);

  @override
  String toString() => message;
}

/// A downloaded file (report, receipt, document).
class Download {
  Download(this.bytes, this.fileName, this.contentType);

  final Uint8List bytes;
  final String fileName;
  final String contentType;
}

/// Thin JSON client for the TMS server with token refresh.
///
/// The access token lives in memory only; the refresh token is handed to
/// [onRefreshTokenChanged] so the session can persist it.
class ApiClient {
  ApiClient({required this.baseUrl, http.Client? httpClient}) : _http = httpClient ?? http.Client();

  String baseUrl;
  final http.Client _http;

  String? accessToken;
  String? refreshToken;

  /// Called whenever the refresh token changes (null = signed out).
  void Function(String? refreshToken)? onRefreshTokenChanged;

  /// Called when a call is refused with a licence code that locks the app.
  void Function(ApiException error)? onBlocked;

  /// Called when the session cannot be refreshed any more, with the reason.
  void Function(String message)? onSessionExpired;

  /// Called after a successful refresh with the fresh `/me` payload.
  void Function(Map<String, dynamic> me)? onUserRefreshed;

  static const _timeout = Duration(seconds: 25);
  Completer<bool>? _refreshing;
  String? _lastRefreshError;

  Uri uri(String path, [Map<String, Object?>? query]) {
    final q = <String, String>{};
    query?.forEach((k, v) {
      if (v != null && v.toString().isNotEmpty) q[k] = v.toString();
    });
    final base = baseUrl.endsWith('/') ? baseUrl.substring(0, baseUrl.length - 1) : baseUrl;
    return Uri.parse('$base$path').replace(queryParameters: q.isEmpty ? null : q);
  }

  Future<dynamic> get(String path, {Map<String, Object?>? query}) => send('GET', path, query: query);
  Future<dynamic> post(String path, [Object? body]) => send('POST', path, body: body ?? const {});
  Future<dynamic> put(String path, Object? body) => send('PUT', path, body: body);
  Future<dynamic> delete(String path, {Object? body}) => send('DELETE', path, body: body);

  /// Sends a JSON request and returns the decoded body (null for empty bodies).
  Future<dynamic> send(String method, String path, {Object? body, Map<String, Object?>? query}) async {
    final res = await _withAuth(() {
      final req = http.Request(method, uri(path, query));
      req.headers['Accept'] = 'application/json';
      if (body != null) {
        req.headers['Content-Type'] = 'application/json';
        req.body = jsonEncode(body);
      }
      return req;
    }, path);
    if (res.body.isEmpty) return null;
    return jsonDecode(utf8.decode(res.bodyBytes));
  }

  /// Downloads a binary resource (PDF, Excel, uploaded file).
  Future<Download> download(String path, {Map<String, Object?>? query}) async {
    final res = await _withAuth(() => http.Request('GET', uri(path, query)), path);
    final type = res.headers['content-type'] ?? 'application/octet-stream';
    return Download(res.bodyBytes, _fileName(res.headers['content-disposition']) ?? 'download', type);
  }

  /// Uploads one file as multipart/form-data under the field name `file`.
  Future<dynamic> upload(String path,
      {required List<int> bytes,
      required String fileName,
      Map<String, String> fields = const {}}) async {
    final res = await _withAuth(() {
      final req = http.MultipartRequest('POST', uri(path));
      req.headers['Accept'] = 'application/json';
      req.fields.addAll(fields);
      req.files.add(http.MultipartFile.fromBytes('file', bytes, filename: fileName));
      return req;
    }, path);
    if (res.body.isEmpty) return null;
    return jsonDecode(utf8.decode(res.bodyBytes));
  }

  // ------------------------------------------------------------------ internals

  Future<http.Response> _withAuth(http.BaseRequest Function() build, String path) async {
    var res = await _execute(build());
    final isAuthCall = path.startsWith('/api/auth/login') || path.startsWith('/api/auth/refresh');
    if (res.statusCode == 401 && !isAuthCall && refreshToken != null) {
      if (await refreshSession()) {
        res = await _execute(build());
      }
    }
    if (res.statusCode >= 200 && res.statusCode < 300) return res;
    final error = _error(res);
    if (isAuthCall) throw error;
    if (LicenseCodes.endSession.contains(error.code)) {
      // Blocked or deactivated while signed in: sign out and show why.
      onSessionExpired?.call(error.message);
    } else if (res.statusCode == 401) {
      onSessionExpired?.call(_lastRefreshError ?? tr('Your session has ended. Please sign in again.'));
    } else if (error.blocksApp) {
      onBlocked?.call(error);
    }
    throw error;
  }

  Future<http.Response> _execute(http.BaseRequest req) async {
    final token = accessToken;
    if (token != null) req.headers['Authorization'] = 'Bearer $token';
    try {
      final streamed = await _http.send(req).timeout(_timeout);
      return await http.Response.fromStream(streamed).timeout(_timeout);
    } on TimeoutException {
      throw ApiException(0, tr('The server is taking too long to respond. Check your connection and try again.'));
    } on SocketException {
      throw ApiException(0, tr('Cannot reach the server. Check your internet connection.'));
    } on http.ClientException {
      throw ApiException(0, tr('Cannot reach the server. Check your internet connection.'));
    } on HandshakeException {
      throw ApiException(0, tr('Secure connection to the server failed.'));
    }
  }

  /// Exchanges the refresh token for new tokens. Concurrent callers share one refresh.
  Future<bool> refreshSession() async {
    final pending = _refreshing;
    if (pending != null) return pending.future;
    final completer = Completer<bool>();
    _refreshing = completer;
    try {
      final req = http.Request('POST', uri('/api/auth/refresh'))
        ..headers['Content-Type'] = 'application/json'
        ..headers['Accept'] = 'application/json'
        ..body = jsonEncode({'refreshToken': refreshToken});
      final res = await _execute(req);
      if (res.statusCode == 200) {
        final body = jsonDecode(utf8.decode(res.bodyBytes)) as Map<String, dynamic>;
        applyTokens(body);
        final user = body['user'];
        if (user is Map) onUserRefreshed?.call(Map<String, dynamic>.from(user));
        completer.complete(true);
      } else {
        final error = _error(res);
        if (error.blocksApp && error.code != LicenseCodes.deviceBlocked) {
          // Licence problem: stay signed in so the app can explain it.
          onBlocked?.call(error);
        } else if (res.statusCode == 401 || res.statusCode == 403) {
          _lastRefreshError = error.message;
          clearTokens();
          onSessionExpired?.call(error.message);
        }
        completer.complete(false);
      }
    } on ApiException {
      // Offline: keep the refresh token so we can try again later.
      completer.complete(false);
    } finally {
      _refreshing = null;
    }
    return completer.future;
  }

  void applyTokens(Map<String, dynamic> body) {
    accessToken = body['accessToken'] as String?;
    refreshToken = body['refreshToken'] as String?;
    onRefreshTokenChanged?.call(refreshToken);
  }

  void clearTokens() {
    accessToken = null;
    refreshToken = null;
    onRefreshTokenChanged?.call(null);
  }

  ApiException _error(http.Response res) {
    String? message;
    String? code;
    final fields = <String, String>{};
    try {
      final j = jsonDecode(utf8.decode(res.bodyBytes));
      if (j is Map) {
        code = j['code'] as String?;
        message = (j['detail'] ?? j['title'] ?? j['message']) as String?;
        final errors = j['errors'];
        if (errors is Map) {
          errors.forEach((k, v) => fields['$k'] = '$v');
          if (fields.isNotEmpty) {
            message = fields.entries.map((e) => '${_humanize(e.key)}: ${e.value}').join('\n');
          }
        }
      }
    } catch (_) {
      // Not JSON (proxy error page etc.).
    }
    message ??= switch (res.statusCode) {
      401 => tr('Your session has ended. Please sign in again.'),
      403 => tr('You are not allowed to do this.'),
      404 => tr('Not found.'),
      413 => tr('The file is too large.'),
      >= 500 => tr('The server had a problem. Please try again later.'),
      _ => tr('Request failed ({status}).', {'status': res.statusCode}),
    };
    return ApiException(res.statusCode, message, code: code, fieldErrors: fields);
  }

  static String _humanize(String field) {
    final words = field
        .replaceAllMapped(RegExp(r'([a-z])([A-Z])'), (m) => '${m[1]} ${m[2]!.toLowerCase()}')
        .replaceAll('.', ' ');
    return words.isEmpty ? words : words[0].toUpperCase() + words.substring(1);
  }

  static String? _fileName(String? disposition) {
    if (disposition == null) return null;
    final star = RegExp(r"filename\*=UTF-8''([^;]+)", caseSensitive: false).firstMatch(disposition);
    if (star != null) return Uri.decodeComponent(star.group(1)!);
    final plain = RegExp(r'filename="?([^";]+)"?', caseSensitive: false).firstMatch(disposition);
    return plain?.group(1);
  }
}
