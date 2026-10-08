import 'package:flutter/material.dart';

import '../widgets/common.dart';
import 'payments_screen.dart';
import 'tenants_screen.dart';

class AgreementsScreen extends StatefulWidget {
  const AgreementsScreen({super.key});

  @override
  State<AgreementsScreen> createState() => _AgreementsScreenState();
}

class _AgreementsScreenState extends State<AgreementsScreen> {
  String _q = '';
  AgreementStatus? _status;
  String? _propertyId;

  @override
  Widget build(BuildContext context) {
    final s = context.watchStore;
    final list = s.visibleAgreements.where((a) {
      if (_status != null && a.status != _status) return false;
      if (_propertyId != null && a.propertyId != _propertyId) return false;
      final q = _q.toLowerCase();
      return q.isEmpty ||
          a.code.toLowerCase().contains(q) ||
          s.tenantName(a.tenantId).toLowerCase().contains(q) ||
          s.unitLabel(a.unitId).toLowerCase().contains(q);
    }).toList()
      ..sort((a, b) {
        // Current agreements first, then by end date.
        final c = (b.status.isCurrent ? 1 : 0) - (a.status.isCurrent ? 1 : 0);
        return c != 0 ? c : a.endDate.compareTo(b.endDate);
      });

    return Scaffold(
      floatingActionButton: FloatingActionButton.extended(
        onPressed: () => push(context, const AgreementFormScreen()),
        icon: const Icon(Icons.add),
        label: Text(tr('Agreement')),
      ),
      body: Column(
        children: [
          FilterBar(
            hint: tr('Search ID, tenant, unit…'),
            onSearch: (v) => setState(() => _q = v),
            filters: [
              FilterDropdown<AgreementStatus>(
                label: tr('Status'),
                value: _status,
                items: {for (final t in AgreementStatus.values) t: t.label},
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
                ? EmptyState(message: tr('No agreements found'), icon: Icons.description_outlined)
                : ListView.builder(
                    padding: const EdgeInsets.fromLTRB(12, 8, 12, 88),
                    itemCount: list.length,
                    itemBuilder: (c, i) {
                      final a = list[i];
                      final daysLeft = a.endDate.difference(today()).inDays;
                      return Card(
                        child: ListTile(
                          leading: const CircleAvatar(child: Icon(Icons.description_outlined)),
                          title: Text('${s.tenantName(a.tenantId)} • ${s.unitFullLabel(a.unitId)}'),
                          subtitle: Text(
                              '${a.code} • ${fmtDate(a.startDate)} → ${fmtDate(a.endDate)}'
                              '${a.status.isCurrent ? ' (${tr('{n} days left', {'n': daysLeft})})' : ''}\n'
                              '${tr('{amount}/month', {'amount': fmtMoney(a.totalMonthly, s.currency)})}'),
                          isThreeLine: true,
                          trailing: StatusChip(a.status),
                          onTap: () => push(context, AgreementDetailScreen(agreementId: a.id)),
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

/// Agreement detail. Shows management actions to staff only.
class AgreementDetailScreen extends StatelessWidget {
  const AgreementDetailScreen({super.key, required this.agreementId, this.embedded = false});

  final String agreementId;

  /// When true, renders only the body (used inside the tenant portal).
  final bool embedded;

  @override
  Widget build(BuildContext context) {
    final s = context.watchStore;
    final a = s.agreementById(agreementId);
    if (a == null) return Scaffold(body: EmptyState(message: tr('Agreement not found')));
    final cur = s.currency;
    final invoices = s.invoices.where((i) => i.agreementId == a.id).toList()
      ..sort((x, y) => y.billingMonth.compareTo(x.billingMonth));
    final staff = s.isStaff;

    final body = ListView(
      padding: const EdgeInsets.all(16),
      children: [
        InfoCard(
          title: tr('Agreement {code}', {'code': a.code}),
          trailing: StatusChip(a.status),
          children: [
            InfoRow(tr('Tenant'), s.tenantName(a.tenantId)),
            InfoRow(tr('Property'), s.propertyName(a.propertyId)),
            InfoRow(tr('Unit'), s.unitLabel(a.unitId)),
            InfoRow(tr('Period'), '${fmtDate(a.startDate)} → ${fmtDate(a.endDate)}'),
            if (a.status.isCurrent)
              InfoRow(tr('Days remaining'), '${a.endDate.difference(today()).inDays}'),
            InfoRow(tr('Monthly rent'), fmtMoney(a.monthlyRent, cur)),
            InfoRow(tr('Service charge'), fmtMoney(a.serviceCharge, cur)),
            InfoRow(tr('Utility'), fmtMoney(a.utilityCharge, cur)),
            InfoRow(tr('Other charge'), fmtMoney(a.otherCharge, cur)),
            InfoRow(tr('Total monthly'), fmtMoney(a.totalMonthly, cur)),
            InfoRow(tr('Security deposit'), fmtMoney(a.securityDeposit, cur)),
            InfoRow(tr('Advance amount'), fmtMoney(a.advanceAmount, cur)),
            InfoRow(tr('Payment due day'), tr('Day {day} of each month', {'day': a.dueDay})),
            InfoRow(tr('Agreement document'), a.documentRef),
            if (a.renewedFromId != null) InfoRow(tr('Renewed from'), a.renewedFromId!),
            if (a.terminatedAt != null)
              InfoRow(tr('Terminated'), '${fmtDate(a.terminatedAt)} — ${a.terminationReason}'),
          ],
        ),
        InfoCard(title: tr('Terms and conditions'), children: [
          Text(a.terms.isEmpty ? tr('No terms recorded') : a.terms),
        ]),
        if (staff)
          Padding(
            padding: const EdgeInsets.symmetric(vertical: 8),
            child: Wrap(spacing: 8, runSpacing: 8, children: [
              if (a.status == AgreementStatus.draft) ...[
                FilledButton.icon(
                  icon: const Icon(Icons.play_arrow),
                  label: Text(tr('Activate (move in)')),
                  onPressed: () async {
                    if (await confirmDialog(context, tr('Activate agreement'),
                            tr('The unit will be marked occupied and the tenant active.')) &&
                        context.mounted) {
                      runGuarded(context, () => s.activateAgreement(a), success: tr('Agreement activated'));
                    }
                  },
                ),
                OutlinedButton.icon(
                  icon: const Icon(Icons.edit),
                  label: Text(tr('Edit draft')),
                  onPressed: () => push(context, AgreementFormScreen(agreement: a)),
                ),
              ],
              if (a.status.isCurrent || a.status == AgreementStatus.expired)
                FilledButton.tonalIcon(
                  icon: const Icon(Icons.autorenew),
                  label: Text(tr('Renew')),
                  onPressed: () => _renew(context, a),
                ),
              if (a.status.isCurrent || a.status == AgreementStatus.draft)
                OutlinedButton.icon(
                  icon: const Icon(Icons.cancel_outlined),
                  label: Text(tr('Terminate')),
                  style: OutlinedButton.styleFrom(foregroundColor: Theme.of(context).colorScheme.error),
                  onPressed: () async {
                    final reason = await promptText(context, tr('Terminate agreement'),
                        label: tr('Reason for termination'));
                    if (reason != null && context.mounted) {
                      runGuarded(context, () => s.terminateAgreement(a, reason),
                          success: tr('Agreement terminated; unit released'));
                    }
                  },
                ),
              OutlinedButton.icon(
                icon: const Icon(Icons.person),
                label: Text(tr('Tenant profile')),
                onPressed: () => push(context, TenantDetailScreen(tenantId: a.tenantId)),
              ),
            ]),
          ),
        SectionHeader(tr('Rent invoices ({n})', {'n': invoices.length})),
        if (invoices.isEmpty) Text(tr('No rent generated yet')),
        for (final i in invoices)
          Card(
            child: ListTile(
              title: Text(fmtMonth(i.billingMonth)),
              subtitle: Text(tr('Total {total} • Paid {paid} • Due {due}', {
                'total': fmtMoney(i.totalAmount, cur),
                'paid': fmtMoney(i.paidAmount, cur),
                'due': fmtMoney(i.dueAmount, cur),
              })),
              trailing: StatusChip(i.status),
              onTap: staff && i.status.isOpen
                  ? () => push(context, RecordPaymentScreen(invoiceId: i.id))
                  : null,
            ),
          ),
      ],
    );
    if (embedded) return body;
    return Scaffold(appBar: AppBar(title: Text(tr('Agreement {code}', {'code': a.code}))), body: body);
  }

  Future<void> _renew(BuildContext context, Agreement a) async {
    final s = context.store;
    final rent = TextEditingController(text: a.monthlyRent.toStringAsFixed(0));
    DateTime newEnd = addMonths(a.endDate, 12);
    final key = GlobalKey<FormState>();
    final ok = await showDialog<bool>(
      context: context,
      builder: (c) => StatefulBuilder(
        builder: (c, setState) => AlertDialog(
          title: Text(tr('Renew agreement')),
          content: Form(
            key: key,
            child: SizedBox(
              width: 400,
              child: Column(mainAxisSize: MainAxisSize.min, children: [
                Text(tr('New agreement starts {date}', {'date': fmtDate(a.endDate.add(const Duration(days: 1)))})),
                const SizedBox(height: 12),
                DateField(
                  label: tr('New end date'),
                  value: newEnd,
                  firstDate: a.endDate.add(const Duration(days: 2)),
                  onChanged: (d) => setState(() => newEnd = d!),
                ),
                const SizedBox(height: 12),
                AmountField(controller: rent, label: tr('New monthly rent'), required: true),
              ]),
            ),
          ),
          actions: [
            TextButton(onPressed: () => Navigator.pop(c, false), child: Text(tr('Cancel'))),
            FilledButton(
              onPressed: () {
                if (key.currentState!.validate()) Navigator.pop(c, true);
              },
              child: Text(tr('Renew')),
            ),
          ],
        ),
      ),
    );
    if (ok == true && context.mounted) {
      Agreement? renewed;
      final success = await runGuarded(
          context, () async => renewed = await s.renewAgreement(a, newEnd: newEnd, newRent: parseAmount(rent.text)),
          success: tr('Agreement renewed'));
      if (success && renewed != null && context.mounted) {
        Navigator.pushReplacement(
            context, MaterialPageRoute(builder: (_) => AgreementDetailScreen(agreementId: renewed!.id)));
      }
    }
  }
}

class AgreementFormScreen extends StatefulWidget {
  const AgreementFormScreen({super.key, this.agreement, this.tenantId, this.unitId});

  /// Existing draft to edit.
  final Agreement? agreement;

  /// Pre-selected tenant / unit when launched from those screens.
  final String? tenantId;
  final String? unitId;

  @override
  State<AgreementFormScreen> createState() => _AgreementFormScreenState();
}

class _AgreementFormScreenState extends State<AgreementFormScreen> {
  final _key = GlobalKey<FormState>();
  late final a = widget.agreement;
  String? _propertyId;
  String? _unitId;
  String? _tenantId;
  DateTime? _start;
  DateTime? _end;
  final _rent = TextEditingController();
  final _service = TextEditingController();
  final _utility = TextEditingController();
  final _other = TextEditingController();
  final _deposit = TextEditingController();
  final _advance = TextEditingController();
  final _dueDay = TextEditingController(text: '5');
  final _doc = TextEditingController();
  final _terms = TextEditingController(
      text: tr(
          'Rent payable by the due day of each month. Two months notice required before leaving. No structural modification without written consent of the owner.'));

  @override
  void initState() {
    super.initState();
    for (final c in [_rent, _service, _utility, _other]) {
      c.addListener(() => setState(() {}));
    }
    final s = context.store;
    if (a != null) {
      _propertyId = a!.propertyId;
      _unitId = a!.unitId;
      _tenantId = a!.tenantId;
      _start = a!.startDate;
      _end = a!.endDate;
      _rent.text = _n(a!.monthlyRent);
      _service.text = _n(a!.serviceCharge);
      _utility.text = _n(a!.utilityCharge);
      _other.text = _n(a!.otherCharge);
      _deposit.text = _n(a!.securityDeposit);
      _advance.text = _n(a!.advanceAmount);
      _dueDay.text = '${a!.dueDay}';
      _doc.text = a!.documentRef;
      _terms.text = a!.terms;
    } else {
      _tenantId = widget.tenantId;
      _start = today();
      _end = addMonths(today(), 12).subtract(const Duration(days: 1));
      if (widget.unitId != null) {
        final u = s.unitById(widget.unitId)!;
        _propertyId = u.propertyId;
        _selectUnit(u);
      }
    }
  }

  static String _n(double v) => v.toStringAsFixed(v % 1 == 0 ? 0 : 2);

  void _selectUnit(Unit u) {
    _unitId = u.id;
    _rent.text = _n(u.monthlyRent);
    _service.text = _n(u.serviceCharge);
    _utility.text = _n(u.utilityCharge);
    _other.text = _n(u.otherCharge);
    _deposit.text = _n(u.securityDeposit);
    _advance.text = _n(u.monthlyRent);
  }

  Future<void> _save({required bool activate}) async {
    if (!_key.currentState!.validate()) return;
    final s = context.store;
    final target = Agreement(
          id: a?.id ?? '',
          code: a?.code ?? '',
          tenantId: '',
          unitId: '',
          propertyId: '',
          startDate: _start!,
          endDate: _end!,
          monthlyRent: 0,
        );
    target
      ..tenantId = _tenantId!
      ..unitId = _unitId!
      ..propertyId = _propertyId!
      ..startDate = _start!
      ..endDate = _end!
      ..monthlyRent = parseAmount(_rent.text)
      ..serviceCharge = parseAmount(_service.text)
      ..utilityCharge = parseAmount(_utility.text)
      ..otherCharge = parseAmount(_other.text)
      ..securityDeposit = parseAmount(_deposit.text)
      ..advanceAmount = parseAmount(_advance.text)
      ..dueDay = int.parse(latinDigits(_dueDay.text))
      ..documentRef = _doc.text.trim()
      ..terms = _terms.text.trim();
    var id = '';
    final ok = await runGuarded(
      context,
      () async => id = await s.saveAgreement(target, activate: activate),
      success: activate ? tr('Agreement created and activated') : tr('Draft agreement saved'),
    );
    if (ok && mounted) {
      Navigator.pushReplacement(context, MaterialPageRoute(builder: (_) => AgreementDetailScreen(agreementId: id)));
    }
  }

  @override
  Widget build(BuildContext context) {
    final s = context.watchStore;
    final cur = s.currency;
    final props = s.visibleProperties.where((p) => p.status == RecordStatus.active).toList();
    final units = _propertyId == null
        ? <Unit>[]
        : s.unitsOf(_propertyId!).where((u) {
            return u.id == _unitId ||
                u.status == UnitStatus.available ||
                u.status == UnitStatus.reserved;
          }).toList();
    final tenants = s.visibleTenants
        .where((t) => t.id == _tenantId || s.currentAgreementForTenant(t.id) == null)
        .toList()
      ..sort((x, y) => x.name.compareTo(y.name));
    final total = parseAmount(_rent.text) +
        parseAmount(_service.text) +
        parseAmount(_utility.text) +
        parseAmount(_other.text);

    return Scaffold(
      appBar: AppBar(title: Text(a == null ? tr('New rental agreement') : tr('Edit draft {code}', {'code': a!.code}))),
      body: Form(
        key: _key,
        child: Center(
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 900),
            child: ListView(
              padding: const EdgeInsets.all(16),
              children: [
                SectionHeader(tr('Tenant & unit')),
                FormGrid(children: [
                  AppDropdown<String>(
                    label: tr('Tenant *'),
                    value: _tenantId,
                    items: {for (final t in tenants) t.id: '${t.name} (${t.mobile})'},
                    onChanged: (v) => setState(() => _tenantId = v),
                  ),
                  AppDropdown<String>(
                    label: tr('Property *'),
                    value: _propertyId,
                    items: {for (final p in props) p.id: p.name},
                    onChanged: (v) => setState(() {
                      _propertyId = v;
                      _unitId = null;
                    }),
                  ),
                  AppDropdown<String>(
                    key: ValueKey('unit-$_propertyId'),
                    label: tr('Unit (available) *'),
                    value: _unitId,
                    items: {
                      for (final u in units)
                        u.id: '${u.unitNo} • ${u.unitType} • ${fmtMoney(u.totalMonthly, cur)}'
                    },
                    onChanged: (v) => setState(() => _selectUnit(s.unitById(v)!)),
                  ),
                ]),
                if (_propertyId != null && units.isEmpty)
                  Padding(
                    padding: const EdgeInsets.only(top: 8),
                    child: Text(tr('No available units in this property.')),
                  ),
                SectionHeader(tr('Period')),
                FormGrid(children: [
                  DateField(
                    label: tr('Start date *'),
                    value: _start,
                    onChanged: (d) => setState(() {
                      _start = d;
                      if (d != null) _end = addMonths(d, 12).subtract(const Duration(days: 1));
                    }),
                  ),
                  DateField(
                    key: ValueKey('end-$_end'),
                    label: tr('End date *'),
                    value: _end,
                    onChanged: (d) => setState(() => _end = d),
                  ),
                  AppTextField(
                    controller: _dueDay,
                    label: tr('Payment due day (1-28) *'),
                    keyboardType: TextInputType.number,
                    validator: (v) {
                      final n = int.tryParse(latinDigits(v ?? ''));
                      return n == null || n < 1 || n > 28 ? tr('Enter 1-28') : null;
                    },
                  ),
                ]),
                SectionHeader(tr('Rent & deposit')),
                FormGrid(children: [
                  AmountField(controller: _rent, label: tr('Monthly rent *'), required: true),
                  AmountField(controller: _service, label: tr('Service charge')),
                  AmountField(controller: _utility, label: tr('Utility')),
                  AmountField(controller: _other, label: tr('Other charge')),
                  AmountField(controller: _deposit, label: tr('Security deposit')),
                  AmountField(controller: _advance, label: tr('Advance amount')),
                ]),
                Padding(
                  padding: const EdgeInsets.only(top: 8),
                  child: Text(tr('Total monthly: {amount}', {'amount': fmtMoney(total, cur)}),
                      style: Theme.of(context).textTheme.titleMedium),
                ),
                SectionHeader(tr('Document & terms')),
                AppTextField(
                  controller: _doc,
                  label: tr('Agreement document (file location / link)'),
                  hint: tr('e.g. Drive link or Cabinet B / T-10001'),
                ),
                const SizedBox(height: 12),
                AppTextField(controller: _terms, label: tr('Terms and conditions'), maxLines: 5),
                const SizedBox(height: 24),
                Wrap(
                  alignment: WrapAlignment.end,
                  spacing: 8,
                  runSpacing: 8,
                  children: [
                    OutlinedButton(
                      onPressed: () => _save(activate: false),
                      child: Text(tr('Save as draft')),
                    ),
                    FilledButton.icon(
                      icon: const Icon(Icons.check),
                      onPressed: () => _save(activate: true),
                      label: Text(tr('Save & activate (move in)')),
                    ),
                  ],
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
