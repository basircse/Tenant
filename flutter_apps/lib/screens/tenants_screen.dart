import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';

import '../data/app_store.dart';
import '../widgets/common.dart';
import 'agreements_screen.dart';
import 'maintenance_screen.dart';
import 'payments_screen.dart';

class TenantsScreen extends StatefulWidget {
  const TenantsScreen({super.key});

  @override
  State<TenantsScreen> createState() => _TenantsScreenState();
}

class _TenantsScreenState extends State<TenantsScreen> {
  String _q = '';
  TenantStatus? _status;
  String? _propertyId;

  @override
  Widget build(BuildContext context) {
    final s = context.watchStore;
    final list = s.visibleTenants.where((t) {
      if (_status != null && t.status != _status) return false;
      final ag = s.currentAgreementForTenant(t.id);
      if (_propertyId != null && ag?.propertyId != _propertyId) return false;
      final q = _q.toLowerCase();
      return q.isEmpty ||
          t.name.toLowerCase().contains(q) ||
          t.code.toLowerCase().contains(q) ||
          t.mobile.contains(q) ||
          t.email.toLowerCase().contains(q) ||
          t.nid.toLowerCase().contains(q) ||
          (ag != null && s.unitLabel(ag.unitId).toLowerCase().contains(q));
    }).toList()
      ..sort((a, b) => a.name.compareTo(b.name));

    return Scaffold(
      floatingActionButton: FloatingActionButton.extended(
        onPressed: () => push(context, const TenantFormScreen()),
        icon: const Icon(Icons.person_add),
        label: Text(tr('Tenant')),
      ),
      body: Column(
        children: [
          FilterBar(
            hint: tr('Search name, mobile, NID, unit…'),
            onSearch: (v) => setState(() => _q = v),
            filters: [
              FilterDropdown<TenantStatus>(
                label: tr('Status'),
                value: _status,
                items: {for (final t in TenantStatus.values) t: t.label},
                onChanged: (v) => setState(() => _status = v),
              ),
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
                ? EmptyState(message: tr('No tenants found'), icon: Icons.people_outline)
                : ListView.builder(
                    padding: const EdgeInsets.fromLTRB(12, 8, 12, 88),
                    itemCount: list.length,
                    itemBuilder: (c, i) {
                      final t = list[i];
                      final ag = s.currentAgreementForTenant(t.id);
                      final due = s.tenantOutstanding(t.id);
                      return Card(
                        child: ListTile(
                          leading: CircleAvatar(child: Text(t.initials)),
                          title: Text(t.name),
                          subtitle: Text(
                              '${t.code} • ${t.mobile}\n${ag == null ? tr('No active unit') : s.unitFullLabel(ag.unitId)}'),
                          isThreeLine: true,
                          trailing: Column(
                            mainAxisAlignment: MainAxisAlignment.center,
                            crossAxisAlignment: CrossAxisAlignment.end,
                            children: [
                              StatusChip(t.status),
                              if (due > 0)
                                Padding(
                                  padding: const EdgeInsets.only(top: 4),
                                  child: Text(tr('Due {amount}', {'amount': fmtMoney(due, s.currency)}),
                                      style: const TextStyle(color: Color(0xFFC62828), fontSize: 12)),
                                ),
                            ],
                          ),
                          onTap: () => push(context, TenantDetailScreen(tenantId: t.id)),
                        ),
                      );
                    },
                  ),
          ),
        ],
      ),
    );
  }
}

class TenantDetailScreen extends StatelessWidget {
  const TenantDetailScreen({super.key, required this.tenantId});

  final String tenantId;

  @override
  Widget build(BuildContext context) {
    final s = context.watchStore;
    final t = s.tenantById(tenantId);
    if (t == null) return Scaffold(body: EmptyState(message: tr('Tenant not found')));
    final ag = s.currentAgreementForTenant(t.id);
    final history = s.agreements.where((a) => a.tenantId == t.id).toList()
      ..sort((a, b) => b.startDate.compareTo(a.startDate));
    final invoices = s.invoicesForTenant(t.id);
    final payments = s.payments.where((p) => p.tenantId == t.id).toList()
      ..sort((a, b) => b.date.compareTo(a.date));
    final maint = s.maintenance.where((m) => m.tenantId == t.id).toList()
      ..sort((a, b) => b.createdAt.compareTo(a.createdAt));
    final outstanding = s.tenantOutstanding(t.id);
    final cur = s.currency;

    return DefaultTabController(
      length: 5,
      child: Scaffold(
        appBar: AppBar(
          title: Text(t.name),
          actions: [
            IconButton(
              tooltip: tr('Edit'),
              icon: const Icon(Icons.edit),
              onPressed: () => push(context, TenantFormScreen(tenant: t)),
            ),
          ],
          bottom: TabBar(isScrollable: true, tabs: [
            Tab(text: tr('Profile')),
            Tab(text: tr('Agreements')),
            Tab(text: tr('Rent')),
            Tab(text: tr('Payments')),
            Tab(text: tr('Maintenance')),
          ]),
        ),
        body: TabBarView(children: [
          // Profile
          ListView(padding: const EdgeInsets.all(16), children: [
            Row(children: [
              CircleAvatar(radius: 32, child: Text(t.initials, style: const TextStyle(fontSize: 22))),
              const SizedBox(width: 16),
              Expanded(
                child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  Text(t.name, style: Theme.of(context).textTheme.titleLarge),
                  Text(t.code),
                  const SizedBox(height: 4),
                  StatusChip(t.status),
                ]),
              ),
            ]),
            const SizedBox(height: 12),
            ResponsiveGrid(children: [
              StatCard(
                label: tr('Current unit'),
                value: ag == null ? tr('None') : s.unitLabel(ag.unitId),
                subtitle: ag == null ? null : s.propertyName(ag.propertyId),
                icon: Icons.home_outlined,
              ),
              StatCard(
                label: tr('Monthly rent'),
                value: ag == null ? '-' : fmtMoney(ag.totalMonthly, cur),
                icon: Icons.receipt_long_outlined,
              ),
              StatCard(
                label: tr('Outstanding balance'),
                value: fmtMoney(outstanding, cur),
                icon: Icons.warning_amber_outlined,
                color: outstanding > 0 ? const Color(0xFFC62828) : const Color(0xFF2E7D32),
              ),
            ]),
            const SizedBox(height: 8),
            InfoCard(title: tr('Personal information'), children: [
              InfoRow(tr('Mobile'), t.mobile),
              InfoRow(tr('Email'), t.email),
              InfoRow(tr('NID / Passport'), t.nid),
              InfoRow(tr('Date of birth'), fmtDate(t.dateOfBirth)),
              InfoRow(tr('Present address'), t.presentAddress),
              InfoRow(tr('Emergency contact'), t.emergencyContact),
              InfoRow(tr('Occupation'), t.occupation),
              InfoRow(tr('Registered'), fmtDate(t.createdAt)),
            ]),
            TenantDocumentsCard(tenant: t),
            if (ag == null)
              Padding(
                padding: const EdgeInsets.only(top: 8),
                child: FilledButton.icon(
                  icon: const Icon(Icons.home_work_outlined),
                  label: Text(tr('Assign to unit (new agreement)')),
                  onPressed: () => push(context, AgreementFormScreen(tenantId: t.id)),
                ),
              ),
          ]),
          // Agreements
          history.isEmpty
              ? EmptyState(message: tr('No agreements'))
              : ListView(padding: const EdgeInsets.all(12), children: [
                  for (final a in history)
                    Card(
                      child: ListTile(
                        title: Text('${a.code} • ${s.unitFullLabel(a.unitId)}'),
                        subtitle: Text(
                            '${fmtDate(a.startDate)} → ${fmtDate(a.endDate)} • ${tr('{amount}/month', {'amount': fmtMoney(a.totalMonthly, cur)})}'),
                        trailing: StatusChip(a.status),
                        onTap: () => push(context, AgreementDetailScreen(agreementId: a.id)),
                      ),
                    ),
                ]),
          // Rent
          invoices.isEmpty
              ? EmptyState(message: tr('No rent records'))
              : ListView(padding: const EdgeInsets.all(12), children: [
                  for (final i in invoices)
                    Card(
                      child: ListTile(
                        title: Text('${fmtMonth(i.billingMonth)} • ${i.code}'),
                        subtitle: Text(tr('Total {total} • Paid {paid} • Due {due}', {
                          'total': fmtMoney(i.totalAmount, cur),
                          'paid': fmtMoney(i.paidAmount, cur),
                          'due': fmtMoney(i.dueAmount, cur),
                        })),
                        trailing: StatusChip(i.status),
                        onTap: i.status.isOpen
                            ? () => push(context, RecordPaymentScreen(invoiceId: i.id))
                            : null,
                      ),
                    ),
                ]),
          // Payments
          payments.isEmpty
              ? EmptyState(message: tr('No payments'))
              : ListView(padding: const EdgeInsets.all(12), children: [
                  for (final p in payments) PaymentTile(payment: p),
                ]),
          // Maintenance
          maint.isEmpty
              ? EmptyState(message: tr('No maintenance requests'))
              : ListView(padding: const EdgeInsets.all(12), children: [
                  for (final m in maint) MaintenanceTile(request: m),
                ]),
        ]),
      ),
    );
  }
}

/// Documents and portal login of a tenant, loaded from the server on demand.
class TenantDocumentsCard extends StatefulWidget {
  const TenantDocumentsCard({super.key, required this.tenant});

  final Tenant tenant;

  @override
  State<TenantDocumentsCard> createState() => _TenantDocumentsCardState();
}

class _TenantDocumentsCardState extends State<TenantDocumentsCard> {
  TenantDetail? _detail;
  String? _error;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    try {
      final d = await context.store.tenantDetail(widget.tenant.id);
      if (mounted) {
        setState(() {
          _detail = d;
          _error = null;
        });
      }
    } on BusinessException catch (e) {
      if (mounted) setState(() => _error = e.message);
    }
  }

  static IconData _icon(TenantDocument d) {
    final type = d.contentType ?? '';
    if (type.startsWith('image/')) return Icons.image_outlined;
    if (type == 'application/pdf') return Icons.picture_as_pdf_outlined;
    return Icons.insert_drive_file_outlined;
  }

  @override
  Widget build(BuildContext context) {
    final s = context.store;
    final t = widget.tenant;
    final d = _detail;
    final docs = d?.documents ?? const <TenantDocument>[];
    return Column(children: [
      InfoCard(title: tr('Portal login'), children: [
        InfoRow(
            tr('Tenant app'),
            d == null
                ? (_error ?? tr('Loading…'))
                : d.portalUsername == null
                    ? tr('Not created (add one under Users)')
                    : '${d.portalUsername} (${d.portalStatus?.label ?? '-'})'),
      ]),
      InfoCard(
        title: tr('Documents ({n})', {'n': docs.length}),
        trailing: TextButton.icon(
          icon: const Icon(Icons.upload_file),
          label: Text(tr('Add')),
          onPressed: d == null ? null : () => _addDocument(context, t),
        ),
        children: d == null
            ? [Text(_error ?? tr('Loading…'))]
            : docs.isEmpty
                ? [Text(tr('No documents yet'))]
                : [
                    for (final doc in docs)
                      ListTile(
                        contentPadding: EdgeInsets.zero,
                        leading: Icon(_icon(doc)),
                        title: Text(doc.name),
                        subtitle: Text([
                          doc.docType,
                          if (doc.reference.isNotEmpty) doc.reference,
                          if (doc.sizeBytes != null) fmtBytes(doc.sizeBytes!),
                          fmtDate(doc.addedAt),
                        ].join(' • ')),
                        onTap: doc.hasFile
                            ? () => downloadAndOpen(context, () => s.downloadTenantDocument(t, doc))
                            : null,
                        trailing: Row(mainAxisSize: MainAxisSize.min, children: [
                          if (doc.hasFile)
                            IconButton(
                              tooltip: tr('Open'),
                              icon: const Icon(Icons.download_outlined),
                              onPressed: () => downloadAndOpen(context, () => s.downloadTenantDocument(t, doc)),
                            ),
                          IconButton(
                            tooltip: tr('Remove'),
                            icon: const Icon(Icons.delete_outline),
                            onPressed: () async {
                              if (await confirmDialog(context, tr('Remove document'), tr('Remove {name}?', {'name': doc.name}),
                                      destructive: true) &&
                                  context.mounted &&
                                  await runGuarded(context, () => s.removeTenantDocument(t, doc))) {
                                await _load();
                              }
                            },
                          ),
                        ]),
                      ),
                  ],
      ),
    ]);
  }

  Future<void> _addDocument(BuildContext context, Tenant t) async {
    final name = TextEditingController();
    final ref = TextEditingController();
    var type = 'NID';
    PlatformFile? file;
    final key = GlobalKey<FormState>();
    final ok = await showDialog<bool>(
      context: context,
      builder: (c) => StatefulBuilder(
        builder: (c, setState) => AlertDialog(
          title: Text(tr('Add document')),
          content: Form(
            key: key,
            child: SizedBox(
              width: 400,
              child: Column(mainAxisSize: MainAxisSize.min, children: [
                AppTextField(controller: name, label: tr('Document name *'), validator: requiredValidator),
                const SizedBox(height: 12),
                AppDropdown<String>(
                  label: tr('Type'),
                  value: type,
                  items: {
                    'NID': tr('NID'),
                    'Passport': tr('Passport'),
                    'Photo': tr('Photo'),
                    'Agreement': tr('Agreement'),
                    'Other': tr('Other'),
                  },
                  onChanged: (v) => setState(() => type = v!),
                ),
                const SizedBox(height: 12),
                OutlinedButton.icon(
                  icon: const Icon(Icons.attach_file),
                  label: Text(file == null ? tr('Choose file (PDF or photo, max 10 MB)') : file!.name,
                      overflow: TextOverflow.ellipsis),
                  onPressed: () async {
                    final picked = await FilePicker.platform.pickFiles(
                      type: FileType.custom,
                      allowedExtensions: const ['pdf', 'jpg', 'jpeg', 'png', 'webp'],
                      withData: true,
                    );
                    if (picked != null && picked.files.isNotEmpty) {
                      setState(() {
                        file = picked.files.first;
                        if (name.text.isEmpty) name.text = file!.name;
                      });
                    }
                  },
                ),
                const SizedBox(height: 12),
                AppTextField(
                  controller: ref,
                  label: file == null ? tr('Reference / location *') : tr('Reference (optional)'),
                  hint: tr('e.g. NID number or Cabinet A / File 12'),
                  validator: (v) => file == null ? requiredValidator(v) : null,
                ),
              ]),
            ),
          ),
          actions: [
            TextButton(onPressed: () => Navigator.pop(c, false), child: Text(tr('Cancel'))),
            FilledButton(
              onPressed: () {
                if (key.currentState!.validate()) Navigator.pop(c, true);
              },
              child: Text(tr('Add')),
            ),
          ],
        ),
      ),
    );
    if (ok != true || !context.mounted) return;
    final s = context.store;
    final f = file;
    final bytes = f?.bytes;
    if (f != null && bytes == null) {
      showSnack(context, tr('Could not read the file'), error: true);
      return;
    }
    final saved = await runGuarded(
      context,
      () => f == null
          ? s.addTenantDocument(t, TenantDocument(name: name.text.trim(), docType: type, reference: ref.text.trim()))
          : s.uploadTenantDocument(t,
              name: name.text.trim(), docType: type, reference: ref.text.trim(), bytes: bytes!, fileName: f.name),
      success: tr('Document added'),
    );
    if (saved) await _load();
  }
}

class TenantFormScreen extends StatefulWidget {
  const TenantFormScreen({super.key, this.tenant});

  final Tenant? tenant;

  @override
  State<TenantFormScreen> createState() => _TenantFormScreenState();
}

class _TenantFormScreenState extends State<TenantFormScreen> {
  final _key = GlobalKey<FormState>();
  late final t = widget.tenant;
  late final _name = TextEditingController(text: t?.name);
  late final _mobile = TextEditingController(text: t?.mobile);
  late final _email = TextEditingController(text: t?.email);
  late final _nid = TextEditingController(text: t?.nid);
  late final _address = TextEditingController(text: t?.presentAddress);
  late final _emergency = TextEditingController(text: t?.emergencyContact);
  late final _occupation = TextEditingController(text: t?.occupation);
  late DateTime? _dob = t?.dateOfBirth;
  late TenantStatus _status = t?.status ?? TenantStatus.inactive;
  bool _createLogin = false;
  final _username = TextEditingController();
  final _password = TextEditingController();

  Future<void> _save() async {
    final s = context.store;
    final target = Tenant(id: t?.id ?? '', code: t?.code ?? '', name: '', mobile: '', status: t?.status ?? _status);
    target
      ..name = _name.text.trim()
      ..mobile = _mobile.text.trim()
      ..email = _email.text.trim()
      ..nid = _nid.text.trim()
      ..presentAddress = _address.text.trim()
      ..emergencyContact = _emergency.text.trim()
      ..occupation = _occupation.text.trim()
      ..dateOfBirth = _dob;
    // Status is automatic while an agreement is active.
    if (s.currentAgreementForTenant(target.id) == null) target.status = _status;
    final ok = await runGuarded(
      context,
      () => s.saveTenant(
        target,
        loginUsername: _createLogin ? _username.text.trim() : null,
        loginPassword: _createLogin ? _password.text : null,
      ),
      success: tr('Tenant saved'),
    );
    if (ok && mounted) Navigator.pop(context);
  }

  @override
  Widget build(BuildContext context) {
    final s = context.watchStore;
    final hasActive = t != null && s.currentAgreementForTenant(t!.id) != null;
    return FormPage(
      title: t == null ? tr('Register tenant') : tr('Edit tenant'),
      formKey: _key,
      onSave: _save,
      children: [
        FormGrid(children: [
          AppTextField(controller: _name, label: tr('Full name *'), validator: requiredValidator),
          AppTextField(
              controller: _mobile,
              label: tr('Mobile number *'),
              keyboardType: TextInputType.phone,
              validator: mobileValidator),
          AppTextField(
              controller: _email,
              label: tr('Email'),
              keyboardType: TextInputType.emailAddress,
              validator: optionalEmailValidator),
          AppTextField(controller: _nid, label: tr('NID / Passport number *'), validator: requiredValidator),
          DateField(
            label: tr('Date of birth'),
            value: _dob,
            required: false,
            lastDate: today(),
            onChanged: (d) => setState(() => _dob = d),
          ),
          AppTextField(controller: _occupation, label: tr('Occupation')),
          AppTextField(controller: _emergency, label: tr('Emergency contact (name - phone)')),
          AppDropdown<TenantStatus>(
            label: tr('Status'),
            value: hasActive ? TenantStatus.active : _status,
            items: {for (final st in TenantStatus.values) st: st.label},
            onChanged: hasActive ? null : (v) => setState(() => _status = v!),
          ),
        ]),
        const SizedBox(height: 12),
        AppTextField(controller: _address, label: tr('Present address'), maxLines: 2),
        if (t == null) ...[
          const SizedBox(height: 12),
          SwitchListTile(
            contentPadding: EdgeInsets.zero,
            title: Text(tr('Create tenant portal login')),
            subtitle: Text(tr('Lets the tenant view rent, payments and raise maintenance requests')),
            value: _createLogin,
            onChanged: (v) => setState(() {
              _createLogin = v;
              if (v && _username.text.isEmpty) {
                _username.text = _name.text.trim().split(' ').first.toLowerCase();
              }
            }),
          ),
          if (_createLogin)
            FormGrid(children: [
              AppTextField(
                  controller: _username, label: tr('Username *'), latin: true, validator: requiredValidator),
              AppTextField(
                controller: _password,
                label: tr('Temporary password *'),
                obscure: true,
                validator: validatePassword,
              ),
            ]),
        ],
      ],
    );
  }
}
