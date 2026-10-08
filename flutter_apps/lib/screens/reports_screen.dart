import 'package:flutter/material.dart';

import '../widgets/common.dart';

/// Reports are built by the server (`/api/reports/{type}`) so that the screen,
/// the Excel file and the PDF always show the same numbers.
class ReportsScreen extends StatefulWidget {
  const ReportsScreen({super.key});

  @override
  State<ReportsScreen> createState() => _ReportsScreenState();
}

class _ReportsScreenState extends State<ReportsScreen> {
  late final Future<List<Json>> _types = context.store.api.get('/api/reports').then(asJsonList);

  static const _icons = {
    'rent-roll': Icons.list_alt,
    'collections': Icons.payments_outlined,
    'dues': Icons.warning_amber_outlined,
    'expenses': Icons.account_balance_wallet_outlined,
    'profit-loss': Icons.trending_up,
    'occupancy': Icons.apartment,
    'tenants': Icons.people_outline,
  };

  @override
  Widget build(BuildContext context) {
    return FutureBuilder<List<Json>>(
      future: _types,
      builder: (context, snap) {
        if (snap.hasError) return Center(child: Text('${snap.error}'));
        if (!snap.hasData) return const Center(child: CircularProgressIndicator());
        return ListView(padding: const EdgeInsets.all(12), children: [
          for (final t in snap.data!)
            Card(
              child: ListTile(
                leading: Icon(_icons[t['type']] ?? Icons.bar_chart),
                title: Text(str(t['title'])),
                subtitle: Text(t['usesPeriod'] == true ? tr('For a period') : tr('As of today')),
                trailing: const Icon(Icons.chevron_right),
                onTap: () => push(
                    context,
                    ReportViewerScreen(
                        type: str(t['type']), title: str(t['title']), usesPeriod: t['usesPeriod'] == true)),
              ),
            ),
        ]);
      },
    );
  }
}

class ReportViewerScreen extends StatefulWidget {
  const ReportViewerScreen({super.key, required this.type, required this.title, required this.usesPeriod});

  final String type;
  final String title;
  final bool usesPeriod;

  @override
  State<ReportViewerScreen> createState() => _ReportViewerScreenState();
}

class _ReportViewerScreenState extends State<ReportViewerScreen> {
  late DateTime _from = monthStart(today());
  late DateTime _to = today();
  String? _propertyId;
  late Future<Json> _report = _load();

  Map<String, Object?> get _query => {
        if (widget.usesPeriod) 'from': isoDay(_from),
        if (widget.usesPeriod) 'to': isoDay(_to),
        'propertyId': _propertyId,
      };

  Future<Json> _load() async => asJson(await context.store.api.get('/api/reports/${widget.type}', query: _query));

  void _reload() => setState(() => _report = _load());

  String _cell(Object? v, String type) {
    if (v == null) return '';
    return switch (type) {
      'money' => fmtMoney(v as num),
      'date' => fmtDate(parseDay(v)),
      'percent' => fmtPercent((v as num).toDouble()),
      'number' => (v as num) % 1 == 0 ? '${v.toInt()}' : v.toStringAsFixed(2),
      _ => '$v',
    };
  }

  Future<void> _export(String format) => downloadAndOpen(
      context, () => context.store.api.download('/api/reports/${widget.type}', query: {..._query, 'format': format}));

  @override
  Widget build(BuildContext context) {
    final s = context.watchStore;
    return Scaffold(
      appBar: AppBar(title: Text(widget.title), actions: [
        IconButton(tooltip: 'Excel', icon: const Icon(Icons.table_view), onPressed: () => _export('xlsx')),
        IconButton(tooltip: 'PDF', icon: const Icon(Icons.picture_as_pdf_outlined), onPressed: () => _export('pdf')),
      ]),
      body: Column(children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(12, 12, 12, 0),
          child: Wrap(spacing: 12, runSpacing: 8, crossAxisAlignment: WrapCrossAlignment.center, children: [
            if (widget.usesPeriod) ...[
              SizedBox(
                width: 170,
                child: DateField(
                    label: tr('From'),
                    value: _from,
                    onChanged: (d) {
                      if (d != null) {
                        _from = d;
                        _reload();
                      }
                    }),
              ),
              SizedBox(
                width: 170,
                child: DateField(
                    label: tr('To'),
                    value: _to,
                    onChanged: (d) {
                      if (d != null) {
                        _to = d;
                        _reload();
                      }
                    }),
              ),
            ],
            SizedBox(
              width: 220,
              child: FilterDropdown<String>(
                label: tr('Property'),
                value: _propertyId,
                items: {for (final p in s.visibleProperties) p.id: p.name},
                onChanged: (v) {
                  _propertyId = v;
                  _reload();
                },
              ),
            ),
          ]),
        ),
        Expanded(
          child: FutureBuilder<Json>(
            future: _report,
            builder: (context, snap) {
              if (snap.hasError) return Center(child: Text('${snap.error}'));
              if (!snap.hasData) return const Center(child: CircularProgressIndicator());
              final r = snap.data!;
              final columns = asJsonList(r['columns']);
              final rows = asJsonList(r['rows']);
              final totals = r['totals'] == null ? null : asJson(r['totals']);
              final summary = asJsonList(r['summary']);
              return ListView(padding: const EdgeInsets.all(12), children: [
                if (str(r['subtitle']).isNotEmpty) Text(str(r['subtitle'])),
                if (summary.isNotEmpty) ...[
                  const SizedBox(height: 8),
                  Wrap(spacing: 8, runSpacing: 8, children: [
                    for (final x in summary)
                      Chip(label: Text('${x['label']}: ${_cell(x['value'], str(x['type']))}')),
                  ]),
                ],
                const SizedBox(height: 8),
                if (rows.isEmpty)
                  EmptyState(message: tr('Nothing to show for these filters'))
                else
                  Card(
                    child: SingleChildScrollView(
                      scrollDirection: Axis.horizontal,
                      child: DataTable(
                        columns: [
                          for (final c in columns)
                            DataColumn(
                                label: Text(str(c['label'])),
                                numeric: const {'money', 'number', 'percent'}.contains(c['type'])),
                        ],
                        rows: [
                          for (final row in rows)
                            DataRow(cells: [
                              for (final c in columns) DataCell(Text(_cell(row[c['key']], str(c['type'])))),
                            ]),
                          if (totals != null)
                            DataRow(cells: [
                              for (final c in columns)
                                DataCell(Text(_cell(totals[c['key']], str(c['type'])),
                                    style: const TextStyle(fontWeight: FontWeight.bold))),
                            ]),
                        ],
                      ),
                    ),
                  ),
              ]);
            },
          ),
        ),
      ]),
    );
  }
}
