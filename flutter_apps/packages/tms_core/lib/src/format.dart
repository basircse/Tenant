import 'package:intl/intl.dart';

import 'l10n/i18n.dart';

final _money = NumberFormat('#,##0.##', 'en');
final _dateFormats = <String, DateFormat>{};

/// Date formats follow the selected language (month names); digits stay Latin.
DateFormat _df(String pattern) {
  final lang = AppLocale.instance.language.name;
  return _dateFormats.putIfAbsent('$lang|$pattern', () => DateFormat(pattern, lang));
}

DateFormat get _date => _df('dd-MMM-yyyy');
DateFormat get _dateTime => _df('dd-MMM-yyyy hh:mm a');
DateFormat get _month => _df('MMMM yyyy');
DateFormat get _monthShort => _df('MMM yyyy');

/// The organisation's currency symbol; apps set it after loading settings.
String currencySymbol = '৳';

String fmtMoney(num amount, [String? currency]) => '${currency ?? currencySymbol} ${_money.format(amount)}';
String fmtDate(DateTime? d) => d == null ? '-' : _date.format(d);
String fmtDateTime(DateTime? d) => d == null ? '-' : _dateTime.format(d);
String fmtMonth(DateTime d) => _month.format(d);
String fmtMonthShort(DateTime d) => _monthShort.format(d);
String fmtPercent(double v) => '${v.toStringAsFixed(1)}%';

DateTime today() {
  final n = DateTime.now();
  return DateTime(n.year, n.month, n.day);
}

DateTime monthStart(DateTime d) => DateTime(d.year, d.month, 1);
DateTime addMonths(DateTime d, int months) {
  final m = DateTime(d.year, d.month + months, 1);
  final lastDay = DateTime(m.year, m.month + 1, 0).day;
  return DateTime(m.year, m.month, d.day > lastDay ? lastDay : d.day);
}

bool sameMonth(DateTime a, DateTime b) => a.year == b.year && a.month == b.month;

/// Escapes a value for CSV output.
String csvCell(Object? v) {
  final s = (v ?? '').toString();
  if (s.contains(',') || s.contains('"') || s.contains('\n')) {
    return '"${s.replaceAll('"', '""')}"';
  }
  return s;
}

String toCsv(List<String> headers, List<List<Object?>> rows) {
  final b = StringBuffer()..writeln(headers.map(csvCell).join(','));
  for (final r in rows) {
    b.writeln(r.map(csvCell).join(','));
  }
  return b.toString();
}

String fmtBytes(int bytes) {
  if (bytes < 1024) return '$bytes B';
  if (bytes < 1024 * 1024) return '${(bytes / 1024).toStringAsFixed(0)} KB';
  return '${(bytes / (1024 * 1024)).toStringAsFixed(1)} MB';
}
