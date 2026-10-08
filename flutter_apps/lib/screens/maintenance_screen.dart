import 'package:flutter/material.dart';

import '../widgets/common.dart';

class MaintenanceScreen extends StatefulWidget {
  const MaintenanceScreen({super.key});

  @override
  State<MaintenanceScreen> createState() => _MaintenanceScreenState();
}

class _MaintenanceScreenState extends State<MaintenanceScreen> {
  String _q = '';
  MaintenanceStatus? _status;
  Priority? _priority;
  String? _propertyId;
  bool _pendingOnly = true;

  @override
  Widget build(BuildContext context) {
    final s = context.watchStore;
    final list = s.visibleMaintenance.where((m) {
      if (_pendingOnly && !m.status.isPending) return false;
      if (_status != null && m.status != _status) return false;
      if (_priority != null && m.priority != _priority) return false;
      if (_propertyId != null && m.propertyId != _propertyId) return false;
      final q = _q.toLowerCase();
      return q.isEmpty ||
          m.code.toLowerCase().contains(q) ||
          m.title.toLowerCase().contains(q) ||
          s.tenantName(m.tenantId).toLowerCase().contains(q) ||
          s.unitLabel(m.unitId).toLowerCase().contains(q);
    }).toList()
      ..sort((a, b) {
        final p = b.priority.index.compareTo(a.priority.index);
        return p != 0 ? p : b.createdAt.compareTo(a.createdAt);
      });

    final canCreate = s.isStaff || (s.isTenant && s.currentAgreementForTenant(s.currentUser!.tenantId!) != null);

    return Scaffold(
      floatingActionButton: canCreate
          ? FloatingActionButton.extended(
              onPressed: () => push(context, const MaintenanceFormScreen()),
              icon: const Icon(Icons.add),
              label: Text(tr('New request')),
            )
          : null,
      body: Column(
        children: [
          FilterBar(
            hint: tr('Search title, tenant, unit…'),
            onSearch: (v) => setState(() => _q = v),
            filters: [
              FilterChip(
                label: Text(tr('Pending only')),
                selected: _pendingOnly,
                onSelected: (v) => setState(() => _pendingOnly = v),
              ),
              FilterDropdown<MaintenanceStatus>(
                label: tr('Status'),
                value: _status,
                items: {for (final t in MaintenanceStatus.values) t: t.label},
                onChanged: (v) => setState(() => _status = v),
              ),
              FilterDropdown<Priority>(
                label: tr('Priority'),
                value: _priority,
                items: {for (final t in Priority.values) t: t.label},
                onChanged: (v) => setState(() => _priority = v),
              ),
              if (s.isStaff)
                FilterDropdown<String>(
                  label: tr('Property'),
                  value: _propertyId,
                  items: {for (final p in s.visibleProperties) p.id: p.name},
                  onChanged: (v) => setState(() => _propertyId = v),
                ),
            ],
          ),
          Expanded(
            child: list.isEmpty
                ? EmptyState(message: tr('No maintenance requests'), icon: Icons.build_outlined)
                : ListView.builder(
                    padding: const EdgeInsets.fromLTRB(12, 8, 12, 88),
                    itemCount: list.length,
                    itemBuilder: (c, i) => MaintenanceTile(request: list[i]),
                  ),
          ),
        ],
      ),
    );
  }
}

IconData maintenanceIcon(MaintenanceType t) => switch (t) {
      MaintenanceType.electrical => Icons.electrical_services,
      MaintenanceType.plumbing => Icons.plumbing,
      MaintenanceType.gas => Icons.local_fire_department_outlined,
      MaintenanceType.lift => Icons.elevator_outlined,
      MaintenanceType.ac => Icons.ac_unit,
      MaintenanceType.water => Icons.water_drop_outlined,
      MaintenanceType.general => Icons.handyman_outlined,
    };

class MaintenanceTile extends StatelessWidget {
  const MaintenanceTile({super.key, required this.request});

  final MaintenanceRequest request;

  @override
  Widget build(BuildContext context) {
    final s = context.watchStore;
    final m = request;
    return Card(
      child: ListTile(
        leading: CircleAvatar(child: Icon(maintenanceIcon(m.type))),
        title: Text(m.title),
        subtitle: Text('${m.code} • ${m.type.label} • ${fmtDate(m.createdAt)}\n'
            '${s.isTenant ? '' : '${s.tenantName(m.tenantId)} • '}${s.unitFullLabel(m.unitId)}'
            '${m.assignedTo.isNotEmpty ? ' • ${m.assignedTo}' : ''}'),
        isThreeLine: true,
        trailing: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          crossAxisAlignment: CrossAxisAlignment.end,
          children: [StatusChip(m.status), const SizedBox(height: 4), StatusChip(m.priority)],
        ),
        onTap: () => push(context, MaintenanceDetailScreen(requestId: m.id)),
      ),
    );
  }
}

class MaintenanceDetailScreen extends StatelessWidget {
  const MaintenanceDetailScreen({super.key, required this.requestId});

  final String requestId;

  @override
  Widget build(BuildContext context) {
    final s = context.watchStore;
    final m = s.maintenance.where((e) => e.id == requestId).firstOrNull;
    if (m == null) return Scaffold(body: EmptyState(message: tr('Request not found')));
    final next = m.status.next;

    return Scaffold(
      appBar: AppBar(title: Text(tr('Request {code}', {'code': m.code}))),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          InfoCard(title: m.title, trailing: StatusChip(m.status), children: [
            InfoRow(tr('Request ID'), m.code),
            InfoRow(tr('Tenant'), s.tenantName(m.tenantId)),
            InfoRow(tr('Property'), s.propertyName(m.propertyId)),
            InfoRow(tr('Unit'), s.unitLabel(m.unitId)),
            InfoRow(tr('Type'), m.type.label),
            InfoRow(tr('Priority'), m.priority.label),
            InfoRow(tr('Created'), fmtDateTime(m.createdAt)),
            InfoRow(tr('Assigned to'), m.assignedTo),
            InfoRow(tr('Description'), m.description),
            InfoRow(tr('Attachment / photo'), m.attachment),
          ]),
          // Progress stepper.
          Card(
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Row(
                children: [
                  for (final st in MaintenanceStatus.values) ...[
                    Expanded(
                      child: Column(children: [
                        Icon(
                          st.index <= m.status.index ? Icons.check_circle : Icons.radio_button_unchecked,
                          color: st.index <= m.status.index
                              ? Theme.of(context).colorScheme.primary
                              : Theme.of(context).disabledColor,
                        ),
                        const SizedBox(height: 4),
                        Text(st.label,
                            textAlign: TextAlign.center, style: Theme.of(context).textTheme.bodySmall),
                      ]),
                    ),
                  ],
                ],
              ),
            ),
          ),
          if (s.isStaff)
            Padding(
              padding: const EdgeInsets.symmetric(vertical: 8),
              child: Wrap(spacing: 8, runSpacing: 8, children: [
                if (m.status != MaintenanceStatus.closed)
                  OutlinedButton.icon(
                    icon: const Icon(Icons.person_add_alt),
                    label: Text(m.assignedTo.isEmpty ? tr('Assign') : tr('Reassign')),
                    onPressed: () async {
                      final who = await promptText(context, tr('Assign request'),
                          label: tr('Assigned person / vendor'), initial: m.assignedTo);
                      if (who != null && context.mounted) {
                        runGuarded(context, () => s.updateMaintenance(m, assignedTo: who),
                            success: tr('Request assigned'));
                      }
                    },
                  ),
                if (next != null && (m.assignedTo.isNotEmpty || next == MaintenanceStatus.assigned))
                  FilledButton.icon(
                    icon: const Icon(Icons.arrow_forward),
                    label: Text(tr('Mark {status}', {'status': next.label})),
                    onPressed: () async {
                      if (next == MaintenanceStatus.assigned && m.assignedTo.isEmpty) {
                        final who = await promptText(context, tr('Assign request'),
                            label: tr('Assigned person / vendor'));
                        if (who != null && context.mounted) {
                          runGuarded(context, () => s.updateMaintenance(m, assignedTo: who),
                              success: tr('Request assigned'));
                        }
                        return;
                      }
                      final note = await promptText(context, tr('Mark {status}', {'status': next.label}),
                          label: tr('Note (optional)'), required: false);
                      if (note != null && context.mounted) {
                        runGuarded(context, () => s.updateMaintenance(m, status: next, note: note),
                            success: tr('Status updated'));
                      }
                    },
                  ),
              ]),
            ),
          SectionHeader(tr('History')),
          for (final h in m.history.reversed)
            ListTile(
              dense: true,
              leading: StatusChip(h.status),
              title: Text('${h.by} • ${fmtDateTime(h.at)}'),
              subtitle: h.note.isEmpty ? null : Text(h.note),
            ),
        ],
      ),
    );
  }
}

class MaintenanceFormScreen extends StatefulWidget {
  const MaintenanceFormScreen({super.key});

  @override
  State<MaintenanceFormScreen> createState() => _MaintenanceFormScreenState();
}

class _MaintenanceFormScreenState extends State<MaintenanceFormScreen> {
  final _key = GlobalKey<FormState>();
  final _title = TextEditingController();
  final _desc = TextEditingController();
  final _attachment = TextEditingController();
  MaintenanceType _type = MaintenanceType.general;
  Priority _priority = Priority.medium;
  String? _agreementId;

  @override
  void initState() {
    super.initState();
    final s = context.store;
    if (s.isTenant) {
      _agreementId = s.currentAgreementForTenant(s.currentUser!.tenantId!)?.id;
    }
  }

  Future<void> _save() async {
    final s = context.store;
    final a = s.agreementById(_agreementId)!;
    final ok = await runGuarded(
      context,
      () => s.createMaintenance(
        tenantId: a.tenantId,
        unitId: a.unitId,
        title: _title.text.trim(),
        type: _type,
        priority: _priority,
        description: _desc.text.trim(),
        attachment: _attachment.text.trim(),
      ),
      success: tr('Maintenance request submitted'),
    );
    if (ok && mounted) Navigator.pop(context);
  }

  @override
  Widget build(BuildContext context) {
    final s = context.watchStore;
    final current = s.visibleAgreements.where((a) => a.status.isCurrent).toList()
      ..sort((a, b) => s.tenantName(a.tenantId).compareTo(s.tenantName(b.tenantId)));
    return FormPage(
      title: tr('New maintenance request'),
      formKey: _key,
      onSave: _save,
      saveLabel: tr('Submit'),
      children: [
        if (s.isStaff)
          Padding(
            padding: const EdgeInsets.only(bottom: 12),
            child: AppDropdown<String>(
              label: tr('Tenant / unit *'),
              value: _agreementId,
              items: {
                for (final a in current) a.id: '${s.tenantName(a.tenantId)} • ${s.unitFullLabel(a.unitId)}'
              },
              onChanged: (v) => setState(() => _agreementId = v),
            ),
          )
        else if (_agreementId != null)
          Padding(
            padding: const EdgeInsets.only(bottom: 12),
            child: Text(tr('Unit: {unit}', {'unit': s.unitFullLabel(s.agreementById(_agreementId)!.unitId)})),
          ),
        FormGrid(children: [
          AppTextField(controller: _title, label: tr('Title *'), validator: requiredValidator),
          AppDropdown<MaintenanceType>(
            label: tr('Request type *'),
            value: _type,
            items: {for (final t in MaintenanceType.values) t: t.label},
            onChanged: (v) => setState(() => _type = v!),
          ),
          AppDropdown<Priority>(
            label: tr('Priority *'),
            value: _priority,
            items: {for (final t in Priority.values) t: t.label},
            onChanged: (v) => setState(() => _priority = v!),
          ),
          AppTextField(
            controller: _attachment,
            label: tr('Attachment / photo link'),
            hint: tr('Optional link to a photo'),
          ),
        ]),
        const SizedBox(height: 12),
        AppTextField(
            controller: _desc, label: tr('Description *'), maxLines: 4, validator: requiredValidator),
      ],
    );
  }
}
