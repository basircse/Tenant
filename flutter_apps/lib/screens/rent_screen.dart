import 'package:flutter/material.dart';

import '../widgets/common.dart';
import 'payments_screen.dart';

class RentScreen extends StatefulWidget {
  const RentScreen({super.key});

  @override
  State<RentScreen> createState() => _RentScreenState();
}

class _RentScreenState extends State<RentScreen> {
  DateTime _month = monthStart(today());
  bool _allMonths = false;
  String? _propertyId;
  RentStatus? _status;
  String _q = '';

  @override
  void initState() {
    super.initState();
    // Tenants see their full history by default.
    _allMonths = context.store.isTenant;
  }

  Future<void> _generate() async {
    final s = context.store;
    final ok = await confirmDialog(context, tr('Generate rent'),
        tr('Generate rent invoices for {month} for all active agreements that do not have one yet?', {'month': fmtMonth(_month)}),
        confirm: tr('Generate'));
    if (!ok || !mounted) return;
    var n = 0;
    if (await runGuarded(context, () async => n = await s.generateRent(_month)) && mounted) {
      showSnack(context, n == 0 ? tr('No new invoices — all agreements already billed') : tr('Invoices generated: {n}', {'n': n}));
    }
  }

  @override
  Widget build(BuildContext context) {
    final s = context.watchStore;
    final cur = s.currency;
    final list = s.visibleInvoices.where((i) {
      if (!_allMonths && !sameMonth(i.billingMonth, _month)) return false;
      if (_propertyId != null && i.propertyId != _propertyId) return false;
      if (_status != null && i.status != _status) return false;
      final q = _q.toLowerCase();
      return q.isEmpty ||
          i.code.toLowerCase().contains(q) ||
          s.tenantName(i.tenantId).toLowerCase().contains(q) ||
          s.unitLabel(i.unitId).toLowerCase().contains(q);
    }).toList()
      ..sort((a, b) {
        final m = b.billingMonth.compareTo(a.billingMonth);
        return m != 0 ? m : s.tenantName(a.tenantId).compareTo(s.tenantName(b.tenantId));
      });
    final active = list.where((i) => i.status != RentStatus.cancelled);
    final total = active.fold(0.0, (a, i) => a + i.totalAmount);
    final paid = active.fold(0.0, (a, i) => a + i.paidAmount);
    final due = active.fold(0.0, (a, i) => a + i.dueAmount);

    return Scaffold(
      floatingActionButton: s.isStaff
          ? FloatingActionButton.extended(
              onPressed: _allMonths ? null : _generate,
              icon: const Icon(Icons.auto_awesome),
              label: Text(tr('Generate {month}', {'month': fmtMonthShort(_month)})),
            )
          : null,
      body: Column(
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(12, 12, 12, 0),
            child: Wrap(
              crossAxisAlignment: WrapCrossAlignment.center,
              spacing: 8,
              runSpacing: 8,
              children: [
                IconButton(
                  onPressed: _allMonths ? null : () => setState(() => _month = addMonths(_month, -1)),
                  icon: const Icon(Icons.chevron_left),
                ),
                SizedBox(
                  width: 140,
                  child: Text(_allMonths ? tr('All months') : fmtMonth(_month),
                      textAlign: TextAlign.center, style: Theme.of(context).textTheme.titleMedium),
                ),
                IconButton(
                  onPressed: _allMonths ? null : () => setState(() => _month = addMonths(_month, 1)),
                  icon: const Icon(Icons.chevron_right),
                ),
                FilterChip(
                  label: Text(tr('All months')),
                  selected: _allMonths,
                  onSelected: (v) => setState(() => _allMonths = v),
                ),
              ],
            ),
          ),
          FilterBar(
            hint: tr('Search tenant, unit, invoice…'),
            onSearch: s.isStaff ? (v) => setState(() => _q = v) : null,
            filters: [
              if (s.isStaff)
                FilterDropdown<String>(
                  label: tr('Property'),
                  value: _propertyId,
                  items: {for (final p in s.visibleProperties) p.id: p.name},
                  onChanged: (v) => setState(() => _propertyId = v),
                ),
              FilterDropdown<RentStatus>(
                label: tr('Status'),
                value: _status,
                items: {for (final t in RentStatus.values) t: t.label},
                onChanged: (v) => setState(() => _status = v),
              ),
            ],
          ),
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 12),
            child: ResponsiveGrid(minTileWidth: 200, children: [
              StatCard(label: tr('Total billed'), value: fmtMoney(total, cur), icon: Icons.receipt_long),
              StatCard(
                  label: tr('Collected'),
                  value: fmtMoney(paid, cur),
                  icon: Icons.payments,
                  color: const Color(0xFF2E7D32)),
              StatCard(
                  label: tr('Outstanding'),
                  value: fmtMoney(due, cur),
                  icon: Icons.warning_amber,
                  color: const Color(0xFFC62828)),
            ]),
          ),
          Expanded(
            child: list.isEmpty
                ? EmptyState(
                    message: s.isStaff
                        ? tr('No rent records for this selection.\nUse "Generate" to create invoices.')
                        : tr('No rent records'),
                    icon: Icons.receipt_long_outlined,
                  )
                : ListView.builder(
                    padding: const EdgeInsets.fromLTRB(12, 8, 12, 88),
                    itemCount: list.length,
                    itemBuilder: (c, i) => InvoiceTile(invoice: list[i]),
                  ),
          ),
        ],
      ),
    );
  }
}

class InvoiceTile extends StatelessWidget {
  const InvoiceTile({super.key, required this.invoice});

  final RentInvoice invoice;

  @override
  Widget build(BuildContext context) {
    final s = context.watchStore;
    final i = invoice;
    final cur = s.currency;
    final overdueDays = i.daysOverdue(today());
    return Card(
      child: ListTile(
        title: Text(s.isTenant
            ? fmtMonth(i.billingMonth)
            : '${s.tenantName(i.tenantId)} • ${s.unitFullLabel(i.unitId)}'),
        subtitle: Text(
            '${i.code} • ${fmtMonthShort(i.billingMonth)} • ${tr('Due {date}', {'date': fmtDate(i.dueDate)})}'
            '${overdueDays > 0 ? ' (${tr('{n} days overdue', {'n': overdueDays})})' : ''}\n'
            '${tr('Total {total} • Paid {paid} • Balance {balance}', {'total': fmtMoney(i.totalAmount, cur), 'paid': fmtMoney(i.paidAmount, cur), 'balance': fmtMoney(i.dueAmount, cur)})}'),
        isThreeLine: true,
        trailing: StatusChip(i.status),
        onTap: () => showInvoiceSheet(context, i),
      ),
    );
  }
}

void showInvoiceSheet(BuildContext context, RentInvoice i) {
  final s = context.store;
  final cur = s.currency;
  final pays = s.paymentsForInvoice(i.id);
  showModalBottomSheet(
    context: context,
    isScrollControlled: true,
    showDragHandle: true,
    builder: (c) => SafeArea(
      child: SingleChildScrollView(
        padding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(children: [
              Expanded(
                child: Text(tr('Rent invoice {code}', {'code': i.code}), style: Theme.of(c).textTheme.titleLarge),
              ),
              StatusChip(i.status),
            ]),
            const SizedBox(height: 8),
            InfoRow(tr('Tenant'), s.tenantName(i.tenantId)),
            InfoRow(tr('Unit'), s.unitFullLabel(i.unitId)),
            InfoRow(tr('Month'), fmtMonth(i.billingMonth)),
            InfoRow(tr('Due date'), fmtDate(i.dueDate)),
            const Divider(),
            _line(c, tr('Rent'), i.rentAmount, cur),
            if (i.serviceCharge > 0) _line(c, tr('Service charge'), i.serviceCharge, cur),
            if (i.utilityCharge > 0) _line(c, tr('Utility'), i.utilityCharge, cur),
            if (i.otherCharge > 0) _line(c, tr('Other charge'), i.otherCharge, cur),
            const Divider(),
            _line(c, tr('Total due'), i.totalAmount, cur, bold: true),
            _line(c, tr('Paid'), i.paidAmount, cur),
            _line(c, tr('Outstanding'), i.dueAmount, cur, bold: true),
            if (pays.isNotEmpty) ...[
              const SizedBox(height: 12),
              Text(tr('Payments'), style: Theme.of(c).textTheme.titleSmall),
              for (final p in pays) PaymentTile(payment: p),
            ],
            const SizedBox(height: 12),
            if (s.isStaff)
              Wrap(spacing: 8, runSpacing: 8, children: [
                if (i.status.isOpen)
                  FilledButton.icon(
                    icon: const Icon(Icons.payments),
                    label: Text(tr('Record payment')),
                    onPressed: () {
                      Navigator.pop(c);
                      push(context, RecordPaymentScreen(invoiceId: i.id));
                    },
                  ),
                if (i.status.isOpen && i.paidAmount == 0)
                  TextButton.icon(
                    icon: const Icon(Icons.cancel_outlined),
                    label: Text(tr('Cancel invoice')),
                    onPressed: () async {
                      Navigator.pop(c);
                      final reason = await promptText(context, tr('Cancel invoice {code}', {'code': i.code}));
                      if (reason != null && context.mounted) {
                        runGuarded(context, () => s.cancelInvoice(i, reason), success: tr('Invoice cancelled'));
                      }
                    },
                  ),
              ]),
          ],
        ),
      ),
    ),
  );
}

Widget _line(BuildContext c, String label, double v, String cur, {bool bold = false}) {
  final style = bold ? const TextStyle(fontWeight: FontWeight.bold) : null;
  return Padding(
    padding: const EdgeInsets.symmetric(vertical: 2),
    child: Row(children: [
      Expanded(child: Text(label, style: style)),
      Text(fmtMoney(v, cur), style: style),
    ]),
  );
}
