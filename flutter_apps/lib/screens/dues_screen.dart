import 'package:flutter/material.dart';

import '../widgets/common.dart';
import 'payments_screen.dart';
import 'tenants_screen.dart';

/// Outstanding / due management (SRS §12).
class DuesScreen extends StatefulWidget {
  const DuesScreen({super.key});

  @override
  State<DuesScreen> createState() => _DuesScreenState();
}

class _DuesScreenState extends State<DuesScreen> {
  String? _propertyId;
  String? _unitId;
  String? _tenantId;
  DateTime? _month;
  RentStatus? _status;

  @override
  Widget build(BuildContext context) {
    final s = context.watchStore;
    final cur = s.currency;
    final now = today();
    final open = s.visibleInvoices.where((i) => i.status.isOpen && i.dueAmount > 0).toList();
    final list = open.where((i) {
      if (_propertyId != null && i.propertyId != _propertyId) return false;
      if (_unitId != null && i.unitId != _unitId) return false;
      if (_tenantId != null && i.tenantId != _tenantId) return false;
      if (_month != null && !sameMonth(i.billingMonth, _month!)) return false;
      if (_status != null && i.status != _status) return false;
      return true;
    }).toList()
      ..sort((a, b) => b.daysOverdue(now).compareTo(a.daysOverdue(now)));
    final total = list.fold(0.0, (a, i) => a + i.dueAmount);
    final months = open.map((i) => monthStart(i.billingMonth)).toSet().toList()
      ..sort((a, b) => b.compareTo(a));
    final units = s.visibleUnits.where((u) => _propertyId == null || u.propertyId == _propertyId);
    final tenantIds = open.map((i) => i.tenantId).toSet();

    void export() {
      showExportDialog(
        context,
        tr('Outstanding dues'),
        toCsv(
          [tr('Tenant'), tr('Property'), tr('Unit'), tr('Month'), tr('Invoice'), tr('Total'), tr('Paid'), tr('Due'), tr('Due date'), tr('Days overdue'), tr('Status')],
          [
            for (final i in list)
              [
                s.tenantName(i.tenantId),
                s.propertyName(i.propertyId),
                s.unitLabel(i.unitId),
                fmtMonthShort(i.billingMonth),
                i.code,
                i.totalAmount,
                i.paidAmount,
                i.dueAmount,
                fmtDate(i.dueDate),
                i.daysOverdue(now),
                i.status.label,
              ]
          ],
        ),
      );
    }

    return Column(
      children: [
        FilterBar(filters: [
          FilterDropdown<String>(
            label: tr('Property'),
            value: _propertyId,
            items: {for (final p in s.visibleProperties) p.id: p.name},
            onChanged: (v) => setState(() {
              _propertyId = v;
              _unitId = null;
            }),
          ),
          FilterDropdown<String>(
            key: ValueKey('unit-$_propertyId'),
            label: tr('Unit'),
            value: _unitId,
            items: {for (final u in units) u.id: s.unitFullLabel(u.id)},
            onChanged: (v) => setState(() => _unitId = v),
          ),
          FilterDropdown<String>(
            label: tr('Tenant'),
            value: _tenantId,
            items: {for (final id in tenantIds) id: s.tenantName(id)},
            onChanged: (v) => setState(() => _tenantId = v),
          ),
          FilterDropdown<DateTime>(
            label: tr('Month'),
            value: _month,
            items: {for (final m in months) m: fmtMonthShort(m)},
            onChanged: (v) => setState(() => _month = v),
          ),
          FilterDropdown<RentStatus>(
            label: tr('Due status'),
            value: _status,
            items: {
              for (final st in [RentStatus.pending, RentStatus.partiallyPaid, RentStatus.overdue])
                st: st.label
            },
            onChanged: (v) => setState(() => _status = v),
          ),
          OutlinedButton.icon(onPressed: export, icon: const Icon(Icons.download), label: Text(tr('Export'))),
        ]),
        Padding(
          padding: const EdgeInsets.fromLTRB(16, 4, 16, 4),
          child: Row(children: [
            Text(tr('Unpaid invoices: {invoices} • Tenants: {tenants}', {'invoices': list.length, 'tenants': list.map((i) => i.tenantId).toSet().length})),
            const Spacer(),
            Text(tr('Total due: {amount}', {'amount': fmtMoney(total, cur)}),
                style: const TextStyle(fontWeight: FontWeight.bold, color: Color(0xFFC62828))),
          ]),
        ),
        Expanded(
          child: list.isEmpty
              ? EmptyState(message: tr('No outstanding dues 🎉'), icon: Icons.check_circle_outline)
              : SingleChildScrollView(
                  padding: const EdgeInsets.all(12),
                  child: Card(
                    child: ScrollableTable(
                      columns: [
                        DataColumn(label: Text(tr('Tenant'))),
                        DataColumn(label: Text(tr('Unit'))),
                        DataColumn(label: Text(tr('Month'))),
                        DataColumn(label: Text(tr('Due')), numeric: true),
                        DataColumn(label: Text(tr('Days overdue')), numeric: true),
                        DataColumn(label: Text(tr('Status'))),
                        const DataColumn(label: Text('')),
                      ],
                      rows: [
                        for (final i in list)
                          DataRow(cells: [
                            DataCell(Text(s.tenantName(i.tenantId)),
                                onTap: () => push(context, TenantDetailScreen(tenantId: i.tenantId))),
                            DataCell(Text(s.unitFullLabel(i.unitId))),
                            DataCell(Text(fmtMonthShort(i.billingMonth))),
                            DataCell(Text(fmtMoney(i.dueAmount, cur))),
                            DataCell(Text('${i.daysOverdue(now)}',
                                style: TextStyle(
                                    color: i.daysOverdue(now) > 0 ? const Color(0xFFC62828) : null))),
                            DataCell(StatusChip(i.status)),
                            DataCell(TextButton(
                              onPressed: () => push(context, RecordPaymentScreen(invoiceId: i.id)),
                              child: Text(tr('Collect')),
                            )),
                          ]),
                      ],
                    ),
                  ),
                ),
        ),
      ],
    );
  }
}
