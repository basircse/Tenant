import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'package:tms_core/tms_core.dart';

import '../data/tenant_store.dart';

class MaintenanceListScreen extends StatelessWidget {
  const MaintenanceListScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final s = context.watch<TenantStore>();
    return Scaffold(
      floatingActionButton: s.currentAgreement == null
          ? null
          : FloatingActionButton.extended(
              onPressed: () => push(context, const NewRequestScreen()),
              icon: const Icon(Icons.add),
              label: Text(tr('New request')),
            ),
      body: RefreshIndicator(
        onRefresh: s.refresh,
        child: s.maintenance.isEmpty
            ? ListView(children: [
                const SizedBox(height: 80),
                EmptyState(message: tr('No maintenance requests'), icon: Icons.build_outlined),
              ])
            : ListView(padding: const EdgeInsets.fromLTRB(12, 12, 12, 88), children: [
                for (final m in s.maintenance)
                  Card(
                    child: ListTile(
                      title: Text(m.title),
                      subtitle: Text('${m.code} • ${m.type.label} • ${fmtDate(m.createdAt)}'
                          '${m.assignedTo.isNotEmpty ? '\n${tr('Assigned to {name}', {'name': m.assignedTo})}' : ''}'),
                      isThreeLine: m.assignedTo.isNotEmpty,
                      trailing: StatusChip(m.status),
                      onTap: () => push(context, RequestDetailScreen(request: m)),
                    ),
                  ),
              ]),
      ),
    );
  }
}

class RequestDetailScreen extends StatelessWidget {
  const RequestDetailScreen({super.key, required this.request});

  final MaintenanceRequest request;

  @override
  Widget build(BuildContext context) {
    final s = context.watch<TenantStore>();
    final m = s.maintenance.where((x) => x.id == request.id).firstOrNull ?? request;
    return Scaffold(
      appBar: AppBar(title: Text(tr('Request {code}', {'code': m.code}))),
      body: ListView(padding: const EdgeInsets.all(16), children: [
        Row(children: [
          Expanded(child: Text(m.title, style: Theme.of(context).textTheme.titleLarge)),
          StatusChip(m.status),
        ]),
        const SizedBox(height: 8),
        InfoCard(title: tr('Details'), children: [
          InfoRow(tr('Type'), m.type.label),
          InfoRow(tr('Priority'), m.priority.label),
          InfoRow(tr('Flat'), '${m.propertyName} • ${m.unitNo}'),
          InfoRow(tr('Raised on'), fmtDateTime(m.createdAt)),
          if (m.assignedTo.isNotEmpty) InfoRow(tr('Assigned to'), m.assignedTo),
          if (m.description.isNotEmpty) InfoRow(tr('Description'), m.description),
        ]),
        if (m.hasAttachment)
          Align(
            alignment: Alignment.centerLeft,
            child: OutlinedButton.icon(
              icon: const Icon(Icons.attachment),
              label: Text(tr('View photo / file')),
              onPressed: () => downloadAndOpen(context, () => s.maintenanceAttachment(m)),
            ),
          ),
        InfoCard(title: tr('Progress'), children: [
          for (final h in m.history)
            ListTile(
              contentPadding: EdgeInsets.zero,
              dense: true,
              leading: Icon(Icons.circle, size: 12, color: statusColor(context, h.status)),
              title: Text(h.status.label),
              subtitle: Text('${fmtDateTime(h.at)} • ${h.by}${h.note.isNotEmpty ? '\n${h.note}' : ''}'),
            ),
        ]),
      ]),
    );
  }
}

class NewRequestScreen extends StatefulWidget {
  const NewRequestScreen({super.key});

  @override
  State<NewRequestScreen> createState() => _NewRequestScreenState();
}

class _NewRequestScreenState extends State<NewRequestScreen> {
  final _key = GlobalKey<FormState>();
  final _title = TextEditingController();
  final _desc = TextEditingController();
  MaintenanceType _type = MaintenanceType.general;
  Priority _priority = Priority.medium;
  PlatformFile? _photo;

  Future<void> _pick() async {
    final r = await FilePicker.platform.pickFiles(type: FileType.image, withData: true);
    if (r != null && r.files.isNotEmpty) setState(() => _photo = r.files.first);
  }

  Future<void> _save() async {
    if (!_key.currentState!.validate()) return;
    final s = context.read<TenantStore>();
    final ok = await runApi(
      context,
      () => s.createMaintenance(
        title: _title.text.trim(),
        type: _type,
        priority: _priority,
        description: _desc.text.trim(),
        photo: _photo?.bytes,
        photoName: _photo?.name,
      ),
      success: tr('Request sent to your landlord'),
    );
    if (ok && mounted) Navigator.pop(context);
  }

  @override
  Widget build(BuildContext context) {
    return FormPage(
      title: tr('Report a problem'),
      formKey: _key,
      onSave: _save,
      children: [
        AppTextField(controller: _title, label: tr('What is the problem? *'), validator: requiredValidator),
        const SizedBox(height: 12),
        FormGrid(children: [
          AppDropdown<MaintenanceType>(
            label: tr('Type'),
            value: _type,
            items: {for (final t in MaintenanceType.values) t: t.label},
            onChanged: (v) => setState(() => _type = v!),
          ),
          AppDropdown<Priority>(
            label: tr('Urgency'),
            value: _priority,
            items: {for (final p in Priority.values) p: p.label},
            onChanged: (v) => setState(() => _priority = v!),
          ),
        ]),
        const SizedBox(height: 12),
        AppTextField(
            controller: _desc, label: tr('Details (where, since when…) *'), maxLines: 4, validator: requiredValidator),
        const SizedBox(height: 12),
        Align(
          alignment: Alignment.centerLeft,
          child: OutlinedButton.icon(
            icon: const Icon(Icons.photo_camera_outlined),
            label: Text(_photo == null ? tr('Add a photo (optional)') : _photo!.name, overflow: TextOverflow.ellipsis),
            onPressed: _pick,
          ),
        ),
      ],
    );
  }
}
