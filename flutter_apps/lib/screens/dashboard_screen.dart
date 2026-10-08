import 'package:flutter/material.dart';

import '../widgets/common.dart';
import 'agreements_screen.dart';
import 'maintenance_screen.dart';
import 'tenants_screen.dart';

class DashboardScreen extends StatelessWidget {
  const DashboardScreen({super.key, required this.onNavigate});

  final void Function(String section) onNavigate;

  @override
  Widget build(BuildContext context) {
    final s = context.watchStore;
    final cur = s.currency;
    final now = today();
    final props = s.visibleProperties;
    final units = s.visibleUnits.where((u) => u.status != UnitStatus.inactive).toList();
    final occupied = units.where((u) => u.status == UnitStatus.occupied).length;
    final vacant = units.where((u) => u.status == UnitStatus.available).length;
    final occupancy = units.isEmpty ? 0.0 : occupied * 100 / units.length;
    final agreements = s.visibleAgreements;
    final activeTenants = agreements.where((a) => a.status.isCurrent).map((a) => a.tenantId).toSet();
    final invoices = s.visibleInvoices.where((i) => i.status != RentStatus.cancelled).toList();
    final monthInv = invoices.where((i) => sameMonth(i.billingMonth, now)).toList();
    final expected = monthInv.fold(0.0, (a, i) => a + i.totalAmount);
    final collected = monthInv.fold(0.0, (a, i) => a + i.paidAmount);
    final outstanding = invoices.where((i) => i.status.isOpen).fold(0.0, (a, i) => a + i.dueAmount);
    final pendingMaint = s.visibleMaintenance.where((m) => m.status.isPending).toList()
      ..sort((a, b) => b.priority.index.compareTo(a.priority.index));
    final expiring = agreements.where((a) => a.status == AgreementStatus.expiring).toList()
      ..sort((a, b) => a.endDate.compareTo(b.endDate));
    final overdue = invoices.where((i) => i.status == RentStatus.overdue).toList()
      ..sort((a, b) => b.dueAmount.compareTo(a.dueAmount));

    // Last 6 months collection trend.
    final months = [for (var i = -5; i <= 0; i++) monthStart(addMonths(now, i))];
    final trend = [
      for (final m in months)
        (
          m,
          invoices.where((i) => sameMonth(i.billingMonth, m)).fold(0.0, (a, i) => a + i.totalAmount),
          invoices.where((i) => sameMonth(i.billingMonth, m)).fold(0.0, (a, i) => a + i.paidAmount),
        )
    ];

    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        Text(tr('Welcome, {name}', {'name': s.currentUser!.name}), style: Theme.of(context).textTheme.headlineSmall),
        Text('${fmtDate(now)} • ${s.isAdmin ? tr('All properties') : tr('Assigned properties: {n}', {'n': props.length})}',
            style: Theme.of(context).textTheme.bodyMedium),
        const SizedBox(height: 16),
        ResponsiveGrid(children: [
          StatCard(
              label: tr('Properties'),
              value: '${props.length}',
              icon: Icons.apartment,
              onTap: () => onNavigate('Properties')),
          StatCard(label: tr('Units'), value: '${units.length}', icon: Icons.door_front_door_outlined),
          StatCard(
              label: tr('Active Tenants'),
              value: '${activeTenants.length}',
              icon: Icons.people_outline,
              onTap: () => onNavigate('Tenants')),
          StatCard(
              label: tr('Occupied'),
              value: '$occupied',
              icon: Icons.home,
              color: const Color(0xFF1565C0)),
          StatCard(
              label: tr('Vacant'),
              value: '$vacant',
              icon: Icons.home_outlined,
              color: const Color(0xFF2E7D32)),
          StatCard(
              label: tr('Occupancy'),
              value: fmtPercent(occupancy),
              icon: Icons.pie_chart_outline,
              color: const Color(0xFF6A1B9A)),
          StatCard(
              label: tr('Expected Rent ({month})', {'month': fmtMonthShort(now)}),
              value: fmtMoney(expected, cur),
              icon: Icons.receipt_long_outlined,
              onTap: () => onNavigate('Rent')),
          StatCard(
              label: tr('Collected ({month})', {'month': fmtMonthShort(now)}),
              value: fmtMoney(collected, cur),
              icon: Icons.payments_outlined,
              color: const Color(0xFF2E7D32),
              onTap: () => onNavigate('Payments')),
          StatCard(
              label: tr('Total Outstanding'),
              value: fmtMoney(outstanding, cur),
              icon: Icons.warning_amber_outlined,
              color: const Color(0xFFC62828),
              onTap: () => onNavigate('Dues')),
          StatCard(
              label: tr('Pending Maintenance'),
              value: '${pendingMaint.length}',
              icon: Icons.build_outlined,
              color: const Color(0xFFEF6C00),
              onTap: () => onNavigate('Maintenance')),
          StatCard(
              label: tr('Agreements Expiring Soon'),
              value: '${expiring.length}',
              icon: Icons.event_busy_outlined,
              color: const Color(0xFFEF6C00),
              onTap: () => onNavigate('Agreements')),
        ]),
        const SizedBox(height: 8),
        Card(
          child: Padding(
            padding: const EdgeInsets.all(16),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(tr('Collection — {month}', {'month': fmtMonth(now)}),
                    style: Theme.of(context).textTheme.titleMedium),
                const SizedBox(height: 8),
                LinearProgressIndicator(
                  value: expected == 0 ? 0 : (collected / expected).clamp(0, 1),
                  minHeight: 10,
                  borderRadius: BorderRadius.circular(5),
                ),
                const SizedBox(height: 6),
                Text(tr('{collected} of {expected} ({percent})', {
                  'collected': fmtMoney(collected, cur),
                  'expected': fmtMoney(expected, cur),
                  'percent': fmtPercent(expected == 0 ? 0 : collected * 100 / expected),
                })),
                const SizedBox(height: 20),
                Text(tr('Last 6 months'), style: Theme.of(context).textTheme.titleSmall),
                const SizedBox(height: 8),
                _TrendChart(data: trend),
              ],
            ),
          ),
        ),
        LayoutBuilder(builder: (context, c) {
          final cards = [
            InfoCard(
              title: tr('Agreements expiring soon'),
              children: expiring.isEmpty
                  ? [Text(tr('None'))]
                  : [
                      for (final a in expiring.take(6))
                        ListTile(
                          contentPadding: EdgeInsets.zero,
                          dense: true,
                          title: Text(s.tenantName(a.tenantId)),
                          subtitle: Text(s.unitFullLabel(a.unitId)),
                          trailing: Text('${tr('{n} days', {'n': a.endDate.difference(now).inDays})}\n${fmtDate(a.endDate)}',
                              textAlign: TextAlign.right),
                          onTap: () => push(context, AgreementDetailScreen(agreementId: a.id)),
                        ),
                    ],
            ),
            InfoCard(
              title: tr('Top overdue'),
              children: overdue.isEmpty
                  ? [Text(tr('No overdue rent 🎉'))]
                  : [
                      for (final i in overdue.take(6))
                        ListTile(
                          contentPadding: EdgeInsets.zero,
                          dense: true,
                          title: Text(s.tenantName(i.tenantId)),
                          subtitle: Text('${s.unitFullLabel(i.unitId)} • ${fmtMonthShort(i.billingMonth)}'),
                          trailing: Text(
                              '${fmtMoney(i.dueAmount, cur)}\n${tr('{n} days', {'n': i.daysOverdue(now)})}',
                              textAlign: TextAlign.right,
                              style: const TextStyle(color: Color(0xFFC62828))),
                          onTap: () => push(context, TenantDetailScreen(tenantId: i.tenantId)),
                        ),
                    ],
            ),
            InfoCard(
              title: tr('Pending maintenance'),
              children: pendingMaint.isEmpty
                  ? [Text(tr('None'))]
                  : [
                      for (final m in pendingMaint.take(6))
                        ListTile(
                          contentPadding: EdgeInsets.zero,
                          dense: true,
                          title: Text(m.title),
                          subtitle: Text('${s.unitFullLabel(m.unitId)} • ${m.status.label}'),
                          trailing: StatusChip(m.priority),
                          onTap: () => push(context, MaintenanceDetailScreen(requestId: m.id)),
                        ),
                    ],
            ),
          ];
          if (c.maxWidth > 1100) {
            return Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [for (final card in cards) Expanded(child: card)],
            );
          }
          return Column(children: cards);
        }),
      ],
    );
  }
}

class _TrendChart extends StatelessWidget {
  const _TrendChart({required this.data});

  final List<(DateTime, double, double)> data;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final maxV = data.fold(0.0, (m, d) => d.$2 > m ? d.$2 : m);
    return SizedBox(
      height: 160,
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.end,
        children: [
          for (final d in data)
            Expanded(
              child: Tooltip(
                message:
                    '${fmtMonth(d.$1)}\n${tr('Expected: {amount}', {'amount': fmtMoney(d.$2, context.store.currency)})}\n${tr('Collected: {amount}', {'amount': fmtMoney(d.$3, context.store.currency)})}',
                child: Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 6),
                  child: Column(
                    mainAxisAlignment: MainAxisAlignment.end,
                    children: [
                      Expanded(
                        child: Row(
                          crossAxisAlignment: CrossAxisAlignment.end,
                          children: [
                            _bar(maxV == 0 ? 0 : d.$2 / maxV, scheme.primaryContainer),
                            const SizedBox(width: 2),
                            _bar(maxV == 0 ? 0 : d.$3 / maxV, scheme.primary),
                          ],
                        ),
                      ),
                      const SizedBox(height: 4),
                      Text(fmtMonthShort(d.$1).split(' ').first,
                          style: Theme.of(context).textTheme.bodySmall),
                    ],
                  ),
                ),
              ),
            ),
        ],
      ),
    );
  }

  Widget _bar(double frac, Color color) => Expanded(
        child: FractionallySizedBox(
          heightFactor: frac.clamp(0.0, 1.0) == 0 ? 0.01 : frac.clamp(0.0, 1.0),
          alignment: Alignment.bottomCenter,
          child: Container(
            decoration: BoxDecoration(
              color: color,
              borderRadius: const BorderRadius.vertical(top: Radius.circular(4)),
            ),
          ),
        ),
      );
}
