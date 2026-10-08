import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../widgets/common.dart';

class PaymentsScreen extends StatefulWidget {
  const PaymentsScreen({super.key});

  @override
  State<PaymentsScreen> createState() => _PaymentsScreenState();
}

class _PaymentsScreenState extends State<PaymentsScreen> {
  String _q = '';
  PaymentMethod? _method;
  String? _propertyId;
  DateTimeRange? _range;
  bool _showVoided = false;

  @override
  Widget build(BuildContext context) {
    final s = context.watchStore;
    final list = s.visiblePayments.where((p) {
      if (!_showVoided && p.voided) return false;
      if (_method != null && p.method != _method) return false;
      if (_propertyId != null && p.propertyId != _propertyId) return false;
      if (_range != null &&
          (p.date.isBefore(_range!.start) || p.date.isAfter(_range!.end.add(const Duration(days: 1))))) {
        return false;
      }
      final q = _q.toLowerCase();
      return q.isEmpty ||
          p.receiptNo.toLowerCase().contains(q) ||
          p.referenceNo.toLowerCase().contains(q) ||
          s.tenantName(p.tenantId).toLowerCase().contains(q) ||
          s.unitLabel(p.unitId).toLowerCase().contains(q);
    }).toList()
      ..sort((a, b) => b.date.compareTo(a.date));
    final total = list.where((p) => !p.voided).fold(0.0, (a, p) => a + p.amount);

    return Scaffold(
      floatingActionButton: s.isStaff
          ? FloatingActionButton.extended(
              onPressed: () => push(context, const RecordPaymentScreen()),
              icon: const Icon(Icons.add),
              label: Text(tr('Record payment')),
            )
          : null,
      body: Column(
        children: [
          FilterBar(
            hint: tr('Search receipt, reference, tenant…'),
            onSearch: s.isStaff ? (v) => setState(() => _q = v) : null,
            filters: [
              FilterDropdown<PaymentMethod>(
                label: tr('Method'),
                value: _method,
                items: {for (final m in PaymentMethod.values) m: m.label},
                onChanged: (v) => setState(() => _method = v),
              ),
              if (s.isStaff)
                FilterDropdown<String>(
                  label: tr('Property'),
                  value: _propertyId,
                  items: {for (final p in s.visibleProperties) p.id: p.name},
                  onChanged: (v) => setState(() => _propertyId = v),
                ),
              OutlinedButton.icon(
                icon: const Icon(Icons.date_range),
                label: Text(_range == null
                    ? tr('Any date')
                    : '${fmtDate(_range!.start)} – ${fmtDate(_range!.end)}'),
                onPressed: () async {
                  final r = await showDateRangePicker(
                    context: context,
                    firstDate: DateTime(2000),
                    lastDate: DateTime(2100),
                    initialDateRange: _range,
                  );
                  setState(() => _range = r);
                },
              ),
              if (s.isStaff)
                FilterChip(
                  label: Text(tr('Show voided')),
                  selected: _showVoided,
                  onSelected: (v) => setState(() => _showVoided = v),
                ),
            ],
          ),
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 4, 16, 0),
            child: Row(children: [
              Text(tr('Payments: {n}', {'n': list.length})),
              const Spacer(),
              Text(tr('Total: {amount}', {'amount': fmtMoney(total, s.currency)}),
                  style: const TextStyle(fontWeight: FontWeight.bold)),
            ]),
          ),
          Expanded(
            child: list.isEmpty
                ? EmptyState(message: tr('No payments found'), icon: Icons.payments_outlined)
                : ListView.builder(
                    padding: const EdgeInsets.fromLTRB(12, 8, 12, 88),
                    itemCount: list.length,
                    itemBuilder: (c, i) => PaymentTile(payment: list[i]),
                  ),
          ),
        ],
      ),
    );
  }
}

class PaymentTile extends StatelessWidget {
  const PaymentTile({super.key, required this.payment});

  final Payment payment;

  @override
  Widget build(BuildContext context) {
    final s = context.watchStore;
    final p = payment;
    final inv = s.invoiceById(p.invoiceId);
    return Card(
      child: ListTile(
        leading: CircleAvatar(
          backgroundColor: p.voided ? Colors.grey.withValues(alpha: 0.2) : null,
          child: Icon(_methodIcon(p.method)),
        ),
        title: Text(
          '${fmtMoney(p.amount, s.currency)} • ${s.isTenant ? p.method.label : s.tenantName(p.tenantId)}',
          style: p.voided ? const TextStyle(decoration: TextDecoration.lineThrough) : null,
        ),
        subtitle: Text('${p.receiptNo} • ${fmtDate(p.date)} • ${p.method.label}'
            '${inv != null ? ' • ${fmtMonthShort(inv.billingMonth)}' : ''}'
            '${p.voided ? '\n${tr('VOIDED: {reason}', {'reason': p.voidReason})}' : ''}'),
        trailing: const Icon(Icons.receipt_outlined),
        onTap: () => showReceipt(context, p),
      ),
    );
  }
}

IconData _methodIcon(PaymentMethod m) => switch (m) {
      PaymentMethod.cash => Icons.money,
      PaymentMethod.bankTransfer => Icons.account_balance,
      PaymentMethod.bkash => Icons.phone_android,
      PaymentMethod.nagad => Icons.phone_iphone,
      PaymentMethod.other => Icons.payments_outlined,
    };

/// Shows the server-generated receipt, with PDF download and (admin) void.
Future<void> showReceipt(BuildContext context, Payment p) {
  final s = context.store;
  final receipt = s.receipt(p);
  return showDialog(
    context: context,
    builder: (c) => AlertDialog(
      title: Row(children: [
        const Icon(Icons.receipt_long),
        const SizedBox(width: 8),
        Expanded(child: Text(tr('Receipt {no}', {'no': p.receiptNo}))),
      ]),
      content: FutureBuilder<Receipt>(
        future: receipt,
        builder: (c, snap) {
          if (snap.hasError) return Text('$snap.error');
          if (!snap.hasData) {
            return const SizedBox(height: 120, child: Center(child: CircularProgressIndicator()));
          }
          return SingleChildScrollView(
            child: Container(
              padding: const EdgeInsets.all(12),
              decoration: BoxDecoration(
                border: Border.all(color: Theme.of(c).dividerColor),
                borderRadius: BorderRadius.circular(8),
              ),
              child: SelectableText(snap.data!.text, style: const TextStyle(fontFamily: 'monospace', fontSize: 13)),
            ),
          );
        },
      ),
      actions: [
        if (s.isAdmin && !p.voided)
          TextButton(
            style: TextButton.styleFrom(foregroundColor: Theme.of(c).colorScheme.error),
            onPressed: () async {
              Navigator.pop(c);
              final reason = await promptText(context, tr('Void payment {no}', {'no': p.receiptNo}));
              if (reason != null && context.mounted) {
                runGuarded(context, () => s.voidPayment(p, reason),
                    success: tr('Payment voided; invoice balance restored'));
              }
            },
            child: Text(tr('Void')),
          ),
        TextButton.icon(
          icon: const Icon(Icons.copy),
          label: Text(tr('Copy')),
          onPressed: () async {
            final r = await receipt;
            await Clipboard.setData(ClipboardData(text: r.text));
            if (context.mounted) showSnack(context, tr('Receipt copied'));
          },
        ),
        TextButton.icon(
          icon: const Icon(Icons.picture_as_pdf_outlined),
          label: const Text('PDF'),
          onPressed: () => downloadAndOpen(context, () => s.receiptPdf(p)),
        ),
        FilledButton(onPressed: () => Navigator.pop(c), child: Text(tr('Close'))),
      ],
    ),
  );
}

class RecordPaymentScreen extends StatefulWidget {
  const RecordPaymentScreen({super.key, this.invoiceId});

  final String? invoiceId;

  @override
  State<RecordPaymentScreen> createState() => _RecordPaymentScreenState();
}

class _RecordPaymentScreenState extends State<RecordPaymentScreen> {
  final _key = GlobalKey<FormState>();
  String? _tenantId;
  String? _invoiceId;
  final _amount = TextEditingController();
  final _ref = TextEditingController();
  final _remarks = TextEditingController();
  DateTime _date = today();
  PaymentMethod _method = PaymentMethod.cash;

  @override
  void initState() {
    super.initState();
    final inv = context.store.invoiceById(widget.invoiceId);
    if (inv != null) {
      _tenantId = inv.tenantId;
      _invoiceId = inv.id;
      _amount.text = inv.dueAmount.toStringAsFixed(0);
    }
  }

  Future<void> _save() async {
    final s = context.store;
    final inv = s.invoiceById(_invoiceId)!;
    Payment? p;
    final ok = await runGuarded(
      context,
      () async => p = await s.recordPayment(
        invoice: inv,
        amount: parseAmount(_amount.text),
        date: _date,
        method: _method,
        referenceNo: _ref.text,
        remarks: _remarks.text,
      ),
      success: tr('Payment recorded'),
    );
    if (!ok || p == null || !mounted) return;
    final nav = Navigator.of(context);
    final rootContext = nav.context;
    nav.pop();
    if (rootContext.mounted) showReceipt(rootContext, p!);
  }

  @override
  Widget build(BuildContext context) {
    final s = context.watchStore;
    final cur = s.currency;
    final openInvoices = s.visibleInvoices.where((i) => i.status.isOpen).toList();
    final tenantIds = openInvoices.map((i) => i.tenantId).toSet();
    final tenantItems = {
      for (final id in tenantIds) id: '${s.tenantName(id)} (${s.tenantById(id)?.mobile ?? ''})'
    };
    final invoices = openInvoices.where((i) => i.tenantId == _tenantId).toList()
      ..sort((a, b) => a.billingMonth.compareTo(b.billingMonth));
    final inv = s.invoiceById(_invoiceId);
    final amount = parseAmount(_amount.text);

    return FormPage(
      title: tr('Record payment'),
      formKey: _key,
      onSave: _save,
      saveLabel: tr('Save & generate receipt'),
      children: [
        if (tenantItems.isEmpty)
          EmptyState(message: tr('No outstanding invoices. Generate rent first.')),
        FormGrid(children: [
          AppDropdown<String>(
            label: tr('Tenant *'),
            value: _tenantId,
            items: tenantItems,
            onChanged: (v) => setState(() {
              _tenantId = v;
              final first = openInvoices.where((i) => i.tenantId == v).toList()
                ..sort((a, b) => a.billingMonth.compareTo(b.billingMonth));
              _invoiceId = first.isEmpty ? null : first.first.id;
              _amount.text = first.isEmpty ? '' : first.first.dueAmount.toStringAsFixed(0);
            }),
          ),
          AppDropdown<String>(
            key: ValueKey('inv-$_tenantId-$_invoiceId'),
            label: tr('Rent invoice *'),
            value: _invoiceId,
            items: {
              for (final i in invoices)
                i.id: '${fmtMonth(i.billingMonth)} • ${tr('due {amount}', {'amount': fmtMoney(i.dueAmount, cur)})}'
            },
            onChanged: (v) => setState(() {
              _invoiceId = v;
              _amount.text = s.invoiceById(v)!.dueAmount.toStringAsFixed(0);
            }),
          ),
        ]),
        if (inv != null)
          Card(
            margin: const EdgeInsets.symmetric(vertical: 12),
            child: Padding(
              padding: const EdgeInsets.all(12),
              child: Wrap(spacing: 24, runSpacing: 4, children: [
                Text(tr('Unit: {unit}', {'unit': s.unitFullLabel(inv.unitId)})),
                Text(tr('Invoice total: {amount}', {'amount': fmtMoney(inv.totalAmount, cur)})),
                Text(tr('Already paid: {amount}', {'amount': fmtMoney(inv.paidAmount, cur)})),
                Text(tr('Outstanding: {amount}', {'amount': fmtMoney(inv.dueAmount, cur)}),
                    style: const TextStyle(fontWeight: FontWeight.bold)),
                if (amount > 0 && amount < inv.dueAmount)
                  Text(tr('Partial payment — remaining after this: {amount}', {'amount': fmtMoney(inv.dueAmount - amount, cur)}),
                      style: const TextStyle(color: Color(0xFFEF6C00))),
              ]),
            ),
          ),
        FormGrid(children: [
          TextFormField(
            controller: _amount,
            keyboardType: const TextInputType.numberWithOptions(decimal: true),
            inputFormatters: latinDigitInputs,
            decoration: InputDecoration(labelText: tr('Amount *'), prefixText: '$cur '),
            onChanged: (_) => setState(() {}),
            validator: (v) {
              final err = amountValidator(v, allowZero: false);
              if (err != null) return err;
              if (inv != null && parseAmount(v!) > inv.dueAmount + 0.001) {
                return tr('Cannot exceed outstanding {amount}', {'amount': fmtMoney(inv.dueAmount, cur)});
              }
              return null;
            },
          ),
          DateField(
            label: tr('Payment date *'),
            value: _date,
            lastDate: today(),
            onChanged: (d) => setState(() => _date = d!),
          ),
          AppDropdown<PaymentMethod>(
            label: tr('Payment method *'),
            value: _method,
            items: {for (final m in PaymentMethod.values) m: m.label},
            onChanged: (v) => setState(() => _method = v!),
          ),
          AppTextField(
            controller: _ref,
            label: _method == PaymentMethod.cash
                ? tr('Transaction / reference no.')
                : tr('Transaction / reference no. *'),
            validator: (v) =>
                _method != PaymentMethod.cash && (v == null || v.trim().isEmpty) ? tr('Required') : null,
          ),
        ]),
        const SizedBox(height: 12),
        AppTextField(controller: _remarks, label: tr('Remarks'), maxLines: 2),
      ],
    );
  }
}
