import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:intl/intl.dart';
import 'package:intl/number_symbols.dart';
import 'package:intl/number_symbols_data.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'bn_core.dart';

/// Languages the apps can show. Bangla is the default.
enum AppLanguage {
  bn('বাংলা'),
  en('English');

  const AppLanguage(this.nativeName);

  /// The language's name in that language (for the switcher).
  final String nativeName;

  Locale get locale => Locale(name);
}

/// The selected language. Strings are translated with [tr]; changing the
/// language rebuilds every widget, so screens need no special handling.
class AppLocale extends ChangeNotifier {
  AppLocale._();

  static final AppLocale instance = AppLocale._();

  static const _prefKey = 'tms_language';

  AppLanguage _language = AppLanguage.bn;
  SharedPreferences? _prefs;

  AppLanguage get language => _language;
  Locale get locale => _language.locale;
  bool get isBangla => _language == AppLanguage.bn;

  /// Loads the saved language. Call once in `main()` before `runApp`.
  Future<void> load() async {
    _useLatinDigits();
    await initializeDateFormatting('bn');
    await initializeDateFormatting('en');
    registerBangla(bnCore);
    try {
      _prefs = await SharedPreferences.getInstance();
      final saved = _prefs!.getString(_prefKey);
      _language = AppLanguage.values.firstWhere((l) => l.name == saved, orElse: () => AppLanguage.bn);
    } catch (_) {
      // Keep the default.
    }
    Intl.defaultLocale = _language.name;
  }

  Future<void> setLanguage(AppLanguage language) async {
    if (language == _language) return;
    _language = language;
    Intl.defaultLocale = language.name;
    notifyListeners();
    // Widgets read [tr] directly, so rebuild the whole tree (state is kept).
    WidgetsBinding.instance.addPostFrameCallback((_) {
      final root = WidgetsBinding.instance.rootElement;
      if (root != null) _markAllDirty(root);
    });
    await _prefs?.setString(_prefKey, language.name);
  }

  static void _markAllDirty(Element e) {
    e.markNeedsBuild();
    e.visitChildren(_markAllDirty);
  }

  /// Numbers and dates use Latin digits (0-9) in Bangla too.
  static void _useLatinDigits() {
    DateFormat.useNativeDigitsByDefaultFor('bn', false);
    final NumberSymbols? s = numberFormatSymbols['bn'];
    if (s == null || s.ZERO_DIGIT == '0') return;
    numberFormatSymbols['bn'] = NumberSymbols(
      NAME: s.NAME,
      DECIMAL_SEP: s.DECIMAL_SEP,
      GROUP_SEP: s.GROUP_SEP,
      PERCENT: s.PERCENT,
      ZERO_DIGIT: '0',
      PLUS_SIGN: s.PLUS_SIGN,
      MINUS_SIGN: s.MINUS_SIGN,
      EXP_SYMBOL: s.EXP_SYMBOL,
      PERMILL: s.PERMILL,
      INFINITY: s.INFINITY,
      NAN: s.NAN,
      DECIMAL_PATTERN: s.DECIMAL_PATTERN,
      SCIENTIFIC_PATTERN: s.SCIENTIFIC_PATTERN,
      PERCENT_PATTERN: s.PERCENT_PATTERN,
      CURRENCY_PATTERN: s.CURRENCY_PATTERN,
      DEF_CURRENCY_CODE: s.DEF_CURRENCY_CODE,
    );
  }
}

/// For `MaterialApp.localizationsDelegates` / `supportedLocales`.
const tmsLocalizationsDelegates = GlobalMaterialLocalizations.delegates;
final tmsSupportedLocales = [for (final l in AppLanguage.values) l.locale];

final Map<String, String> _bangla = {};
final Set<String> _reported = {};

/// Adds Bangla translations (English text -> Bangla text).
void registerBangla(Map<String, String> translations) => _bangla.addAll(translations);

/// Translates [english] into the current language. `{name}` placeholders are
/// replaced from [args], e.g. `tr('Rent for {month}', {'month': m})`.
String tr(String english, [Map<String, Object?> args = const {}]) {
  var text = english;
  if (AppLocale.instance.isBangla) {
    final bn = _bangla[english];
    if (bn != null) {
      text = bn;
    } else if (kDebugMode && _reported.add(english)) {
      debugPrint('[i18n] missing Bangla for: $english');
    }
  }
  if (args.isNotEmpty) {
    args.forEach((k, v) => text = text.replaceAll('{$k}', '${v ?? ''}'));
  }
  return text;
}

const _banglaZero = 0x09E6; // ০

/// Converts Bangla digits (০-৯) to Latin digits (0-9).
String latinDigits(String s) {
  if (!s.codeUnits.any((c) => c >= _banglaZero && c <= _banglaZero + 9)) return s;
  return String.fromCharCodes(
      s.codeUnits.map((c) => c >= _banglaZero && c <= _banglaZero + 9 ? c - _banglaZero + 0x30 : c));
}

/// Turns Bangla digits into Latin ones as the user types.
class LatinDigitsFormatter extends TextInputFormatter {
  const LatinDigitsFormatter();

  @override
  TextEditingValue formatEditUpdate(TextEditingValue oldValue, TextEditingValue newValue) {
    final text = latinDigits(newValue.text);
    return text == newValue.text ? newValue : newValue.copyWith(text: text);
  }
}

/// `inputFormatters` for number, phone, code and username fields.
const latinDigitInputs = <TextInputFormatter>[LatinDigitsFormatter()];

/// Language picker for profile / settings screens.
class LanguageTile extends StatelessWidget {
  const LanguageTile({super.key});

  @override
  Widget build(BuildContext context) {
    final locale = AppLocale.instance;
    return ListenableBuilder(
      listenable: locale,
      builder: (context, _) => ListTile(
        leading: const Icon(Icons.translate),
        title: Text(tr('Language')),
        trailing: SegmentedButton<AppLanguage>(
          showSelectedIcon: false,
          segments: [
            for (final l in AppLanguage.values) ButtonSegment(value: l, label: Text(l.nativeName)),
          ],
          selected: {locale.language},
          onSelectionChanged: (s) => locale.setLanguage(s.first),
        ),
      ),
    );
  }
}

/// Compact switcher (shows the other language) for sign-in screens.
class LanguageButton extends StatelessWidget {
  const LanguageButton({super.key});

  @override
  Widget build(BuildContext context) {
    final locale = AppLocale.instance;
    return ListenableBuilder(
      listenable: locale,
      builder: (context, _) {
        final other = locale.isBangla ? AppLanguage.en : AppLanguage.bn;
        return TextButton.icon(
          icon: const Icon(Icons.translate, size: 18),
          label: Text(other.nativeName),
          onPressed: () => locale.setLanguage(other),
        );
      },
    );
  }
}
