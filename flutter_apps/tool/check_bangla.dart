// Checks that every `tr('...')` text in the apps has a Bangla translation.
//
//   dart run tool/check_bangla.dart        (from the project root)
//
// Exits with code 1 when a translation is missing or its {placeholders}
// differ from the English text.
import 'dart:io';

const _sources = ['lib', 'tenant_app/lib', 'packages/tms_core/lib'];

// A single- or double-quoted Dart string literal on one line (groups: text).
const _single = r"'((?:[^'\\\n]|\\.)*)'";
const _double = r'"((?:[^"\\\n]|\\.)*)"';
const _literal = '$_single|$_double';

final _trCall = RegExp(r'\btr\(\s*(?:' + _literal + ')');
final _entry = RegExp(r'^\s*(?:' + _literal + r')\s*:\s*(?:' + _literal + ')');
final _mapFile = RegExp(r'Map<String, String> bn\w* = \{|const _bn\w* = \{');
final _placeholder = RegExp(r'\{(\w+)\}');

String _unescape(String s) => s.replaceAllMapped(RegExp(r'\\(.)'), (m) => m[1]!);

Set<String> _names(String s) => _placeholder.allMatches(s).map((m) => m[1]!).toSet();

void main() {
  final files = [
    for (final dir in _sources)
      if (Directory(dir).existsSync())
        ...Directory(dir).listSync(recursive: true).whereType<File>().where((f) => f.path.endsWith('.dart')),
  ];

  final bangla = <String, String>{};
  final used = <String, String>{}; // text -> first file using it
  for (final f in files) {
    final lines = f.readAsLinesSync();
    if (_mapFile.hasMatch(lines.join('\n'))) {
      for (final line in lines) {
        final m = _entry.firstMatch(line);
        if (m != null) bangla[_unescape(m[1] ?? m[2]!)] = _unescape(m[3] ?? m[4]!);
      }
    }
    final code = lines.where((l) => !l.trimLeft().startsWith('//')).join('\n');
    for (final m in _trCall.allMatches(code)) {
      used.putIfAbsent(_unescape(m[1] ?? m[2]!), () => f.path);
    }
  }

  var problems = 0;
  for (final e in used.entries) {
    final bn = bangla[e.key];
    if (bn == null) {
      stdout.writeln('MISSING  ${e.key}    (${e.value})');
      problems++;
    } else if (!_names(bn).containsAll(_names(e.key)) || !_names(e.key).containsAll(_names(bn))) {
      stdout.writeln('PLACEHOLDERS  ${e.key}  =>  $bn');
      problems++;
    }
  }
  stdout.writeln('${used.length} texts used, ${bangla.length} translations, $problems problem(s).');
  if (problems > 0) exitCode = 1;
}
