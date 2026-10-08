import 'package:flutter/material.dart';

import '../widgets/common.dart';

class AuditScreen extends StatefulWidget {
  const AuditScreen({super.key});

  @override
  State<AuditScreen> createState() => _AuditScreenState();
}

class _AuditScreenState extends State<AuditScreen> {
  String _q = '';
  String? _entity;

  @override
  Widget build(BuildContext context) {
    final s = context.watchStore;
    final entities = s.auditLogs.map((l) => l.entityType).where((e) => e.isNotEmpty).toSet().toList()..sort();
    final list = s.auditLogs.where((l) {
      if (_entity != null && l.entityType != _entity) return false;
      final q = _q.toLowerCase();
      return q.isEmpty ||
          l.action.toLowerCase().contains(q) ||
          l.userName.toLowerCase().contains(q) ||
          l.entityId.toLowerCase().contains(q) ||
          l.details.toLowerCase().contains(q);
    }).toList();

    return Column(children: [
      FilterBar(
        hint: tr('Search user, action, record…'),
        onSearch: (v) => setState(() => _q = v),
        filters: [
          FilterDropdown<String>(
            label: tr('Entity'),
            value: _entity,
            items: {for (final e in entities) e: e},
            onChanged: (v) => setState(() => _entity = v),
          ),
          OutlinedButton.icon(
            icon: const Icon(Icons.download),
            label: Text(tr('Export')),
            onPressed: () => showExportDialog(
              context,
              tr('Audit log'),
              toCsv([tr('Date/time'), tr('User'), tr('Action'), tr('Entity'), tr('Record'), tr('Details')], [
                for (final l in list) [fmtDateTime(l.at), l.userName, l.action, l.entityType, l.entityId, l.details]
              ]),
            ),
          ),
        ],
      ),
      Expanded(
        child: list.isEmpty
            ? EmptyState(message: tr('No audit entries'), icon: Icons.history)
            : ListView.separated(
                padding: const EdgeInsets.all(12),
                itemCount: list.length,
                separatorBuilder: (_, __) => const Divider(height: 1),
                itemBuilder: (c, i) {
                  final l = list[i];
                  return ListTile(
                    dense: true,
                    leading: const Icon(Icons.history),
                    title: Text('${l.action}${l.entityId.isNotEmpty ? ' • ${l.entityId}' : ''}'),
                    subtitle: Text('${l.userName} • ${fmtDateTime(l.at)}'
                        '${l.details.isNotEmpty ? '\n${l.details}' : ''}'),
                  );
                },
              ),
      ),
    ]);
  }
}
