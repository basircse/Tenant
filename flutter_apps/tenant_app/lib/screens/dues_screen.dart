import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:provider/provider.dart';
import 'package:tms_core/tms_core.dart';

import '../data/tenant_store.dart';

/// Rent invoices (dues) and payment history with receipts.
class DuesScreen extends StatelessWidget {
  const DuesScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final s = context.watch<TenantStore>();
    return DefaultTabController(
      length: 2,
      child: Column(children: [
        TabBar(tabs: [Tab(text: tr('Rent')), Tab(text: tr('Payments'))]),
        Expanded(
          child: TabBarView(children: [
            RefreshIndicator(
              onRefresh: s.refresh,
              child: s.invoices.isEmpty
                  ? ListView(children: [const SizedBox(height: 80), EmptyState(message: tr('No rent records yet'))])
                  : ListView(padding: const EdgeInsets.all(12), children: [
                      if (s.outstanding > 0)
                        Padding(
                          padding: const EdgeInsets.fromLTRB(4, 0, 4, 8),
                          child: Text(tr('Total due: {amount}', {'amount': fmtMoney(s.outstanding)}),
                              style: Theme.of(context).textTheme.titleMedium),
                        ),
                      for (final i in s.invoices) _InvoiceCard(invoice: i),
                    ]),
            ),
            RefreshIndicator(
              onRefresh: s.refresh,
              child: s.payments.isEmpty
                  ? ListView(children: [const SizedBox(height: 80), EmptyState(message: tr('No payments yet'))])
                  : ListView(padding: const EdgeInsets.all(12), children: [
                      for (final p in s.payments) PaymentCard(payment: p),
                    ]),
            ),
          ]),
        ),
      ]),
    );
  }
}

class _InvoiceCard extends StatelessWidget {
  const _InvoiceCard({required this.invoice});

  final RentInvoice invoice;

  @override
  Widget build(BuildContext context) {
    final i = invoice;
    final s = context.read<TenantStore>();
    final paid = s.payments.where((p) => p.invoiceId == i.id && !p.voided).toList();
    return Card(
      child: ExpansionTile(
        title: Text(fmtMonth(i.billingMonth)),
        subtitle: Text(i.status.isOpen
            ? tr('Due {amount} by {date}', {'amount': fmtMoney(i.dueAmount), 'date': fmtDate(i.dueDate)})
            : '${fmtMoney(i.totalAmount)} • ${i.status.label}'),
        trailing: StatusChip(i.status),
        childrenPadding: const EdgeInsets.fromLTRB(16, 0, 16, 12),
        children: [
          InfoRow(tr('Invoice'), i.code),
          InfoRow(tr('Rent'), fmtMoney(i.rentAmount)),
          if (i.serviceCharge > 0) InfoRow(tr('Service charge'), fmtMoney(i.serviceCharge)),
          if (i.utilityCharge > 0) InfoRow(tr('Utility charge'), fmtMoney(i.utilityCharge)),
          if (i.otherCharge > 0) InfoRow(tr('Other charge'), fmtMoney(i.otherCharge)),
          InfoRow(tr('Total'), fmtMoney(i.totalAmount)),
          InfoRow(tr('Paid'), fmtMoney(i.paidAmount)),
          InfoRow(tr('Balance'), fmtMoney(i.dueAmount)),
          if (i.status == RentStatus.overdue) InfoRow(tr('Days overdue'), '${i.daysOverdue(today())}'),
          if (i.cancelReason.isNotEmpty) InfoRow(tr('Cancelled'), i.cancelReason),
          for (final p in paid)
            ListTile(
              contentPadding: EdgeInsets.zero,
              dense: true,
              leading: const Icon(Icons.check_circle_outline),
              title: Text('${fmtMoney(p.amount)} • ${p.method.label}'),
              subtitle: Text('${fmtDate(p.date)} • ${p.receiptNo}'),
              onTap: () => showReceipt(context, p),
            ),
          if (i.status.isOpen)
            Padding(
              padding: const EdgeInsets.only(top: 8),
              child: Text(tr('Pay your landlord or manager; your payment appears here once it is recorded.')),
            ),
        ],
      ),
    );
  }
}

class PaymentCard extends StatelessWidget {
  const PaymentCard({super.key, required this.payment});

  final Payment payment;

  @override
  Widget build(BuildContext context) {
    final p = payment;
    return Card(
      child: ListTile(
        leading: Icon(p.voided ? Icons.block : Icons.payments_outlined),
        title: Text(fmtMoney(p.amount),
            style: TextStyle(decoration: p.voided ? TextDecoration.lineThrough : null)),
        subtitle: Text([
          '${fmtDate(p.date)} • ${p.method.label}',
          if (p.billingMonth != null) tr('For {month}', {'month': fmtMonth(p.billingMonth!)}),
          if (p.voided) tr('Voided: {reason}', {'reason': p.voidReason}),
        ].join('\n')),
        trailing: Text(p.receiptNo, style: Theme.of(context).textTheme.bodySmall),
        onTap: () => showReceipt(context, p),
      ),
    );
  }
}

Future<void> showReceipt(BuildContext context, Payment p) {
  final s = context.read<TenantStore>();
  final receipt = s.receipt(p);
  return showDialog(
    context: context,
    builder: (c) => AlertDialog(
      title: Text(tr('Receipt {no}', {'no': p.receiptNo})),
      content: FutureBuilder<Receipt>(
        future: receipt,
        builder: (c, snap) {
          if (snap.hasError) return Text('${snap.error}');
          if (!snap.hasData) return const SizedBox(height: 120, child: Center(child: CircularProgressIndicator()));
          return SingleChildScrollView(
            child: SelectableText(snap.data!.text, style: const TextStyle(fontFamily: 'monospace', fontSize: 13)),
          );
        },
      ),
      actions: [
        TextButton.icon(
          icon: const Icon(Icons.copy),
          label: Text(tr('Copy')),
          onPressed: () async {
            await Clipboard.setData(ClipboardData(text: (await receipt).text));
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
