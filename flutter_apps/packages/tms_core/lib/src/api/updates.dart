import '../models/models.dart';
import 'api_client.dart';

/// A published app version (`GET /api/app/releases/{app}`).
class AppRelease {
  AppRelease({this.latestVersion, this.minVersion, this.downloadUrl, this.notes});

  final String? latestVersion;
  final String? minVersion;
  final String? downloadUrl;
  final String? notes;

  factory AppRelease.fromApi(Json j) => AppRelease(
        latestVersion: j['latestVersion'],
        minVersion: j['minVersion'],
        downloadUrl: j['downloadUrl'],
        notes: j['notes'],
      );

  static Future<AppRelease?> fetch(ApiClient api, String app) async {
    try {
      return AppRelease.fromApi(asJson(await api.get('/api/app/releases/$app')));
    } on ApiException {
      return null;
    }
  }
}

/// What the installed version should do about [release].
enum UpdateStatus { none, available, required }

UpdateStatus updateStatus(String installed, AppRelease? release) {
  if (release == null || installed.isEmpty) return UpdateStatus.none;
  final min = release.minVersion;
  if (min != null && compareVersions(installed, min) < 0) return UpdateStatus.required;
  final latest = release.latestVersion;
  if (latest != null && compareVersions(installed, latest) < 0) return UpdateStatus.available;
  return UpdateStatus.none;
}

/// Compares dotted versions numerically ("1.10.0" > "1.9.3"); build suffixes are ignored.
int compareVersions(String a, String b) {
  List<int> parts(String v) =>
      v.split('+').first.split('.').map((p) => int.tryParse(p.replaceAll(RegExp(r'\D'), '')) ?? 0).toList();
  final x = parts(a), y = parts(b);
  for (var i = 0; i < x.length || i < y.length; i++) {
    final d = (i < x.length ? x[i] : 0) - (i < y.length ? y[i] : 0);
    if (d != 0) return d.sign;
  }
  return 0;
}
