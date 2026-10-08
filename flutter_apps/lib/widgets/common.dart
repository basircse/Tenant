import 'dart:async';

import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'package:tms_core/tms_core.dart';

import '../data/app_store.dart';

export 'package:tms_core/tms_core.dart';

extension StoreContext on BuildContext {
  AppStore get store => read<AppStore>();
  AppStore get watchStore => watch<AppStore>();
}

/// Runs a store [action] behind a progress indicator; shows a refusal from the
/// server as a snackbar. Returns true on success.
Future<bool> runGuarded(BuildContext context, FutureOr<void> Function() action, {String? success}) {
  return runApi(context, () async {
    try {
      await action();
    } on BusinessException catch (e) {
      throw ApiException(400, e.message, code: e.code);
    }
  }, success: success);
}
