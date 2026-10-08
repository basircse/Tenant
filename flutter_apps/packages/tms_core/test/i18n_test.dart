import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:tms_core/tms_core.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  setUpAll(() async {
    SharedPreferences.setMockInitialValues({});
    await AppLocale.instance.load();
    registerBangla({'Rent for {month}': '{month} মাসের ভাড়া'});
  });

  test('Bangla is the default language', () {
    expect(AppLocale.instance.language, AppLanguage.bn);
    expect(tr('Language'), 'ভাষা');
  });

  test('placeholders are filled in both languages', () {
    expect(tr('Rent for {month}', {'month': 'March'}), 'March মাসের ভাড়া');
    expect(tr('No translation {x}', {'x': 1}), 'No translation 1');
  });

  test('Bangla digits become Latin', () {
    expect(latinDigits('০১৭১২৩৪৫৬৭৮৯'), '017123456789');
    expect(latinDigits('৫,০০০.৫০ টাকা'), '5,000.50 টাকা');
    expect(latinDigits('abc 123'), 'abc 123');
    const f = LatinDigitsFormatter();
    final out = f.formatEditUpdate(TextEditingValue.empty, const TextEditingValue(text: '১২৩'));
    expect(out.text, '123');
    expect(parseAmount('১,৫০০'.replaceAll(',', '')), 1500);
    expect(amountValidator('২৫০'), isNull);
    expect(mobileValidator('০১৭১১২২৩৩৪৪'), isNull);
  });

  test('dates use Bangla month names with Latin digits', () {
    final s = fmtDate(DateTime(2026, 3, 7));
    expect(s, startsWith('07-'));
    expect(s, endsWith('-2026'));
    expect(s.contains('Mar'), isFalse);
    expect(fmtMoney(500000), '৳ 500,000');
  });

  test('English uses English month names', () async {
    await AppLocale.instance.setLanguage(AppLanguage.en);
    expect(fmtDate(DateTime(2026, 3, 7)), '07-Mar-2026');
    expect(tr('Language'), 'Language');
    await AppLocale.instance.setLanguage(AppLanguage.bn);
  });
}
