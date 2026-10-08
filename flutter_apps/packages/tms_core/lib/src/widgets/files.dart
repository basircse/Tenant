import 'dart:io';

import 'package:flutter/material.dart';
import 'package:open_filex/open_filex.dart';
import 'package:path_provider/path_provider.dart';

import '../api/api_client.dart';
import '../l10n/i18n.dart';
import 'common.dart';

/// Saves a downloaded file and opens it with the device's viewer.
///
/// Files go to the app's documents folder under `TMS`, so they stay available
/// after the app is closed (and can be shared from the viewer).
Future<File> saveDownload(Download d) async {
  final base = await getApplicationDocumentsDirectory();
  final dir = Directory('${base.path}${Platform.pathSeparator}TMS');
  if (!dir.existsSync()) dir.createSync(recursive: true);
  final safe = d.fileName.replaceAll(RegExp(r'[\\/:*?"<>|]'), '_');
  final file = File('${dir.path}${Platform.pathSeparator}$safe');
  await file.writeAsBytes(d.bytes, flush: true);
  return file;
}

/// Downloads with [fetch], saves the file and opens it; errors become snackbars.
Future<void> downloadAndOpen(BuildContext context, Future<Download> Function() fetch) async {
  File? file;
  final ok = await runApi(context, () async => file = await saveDownload(await fetch()));
  if (!ok || file == null) return;
  final result = await OpenFilex.open(file!.path);
  if (result.type != ResultType.done && context.mounted) {
    showSnack(context, tr('Saved to {path}', {'path': file!.path}));
  }
}
