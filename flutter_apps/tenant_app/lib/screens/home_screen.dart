import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'package:tms_core/tms_core.dart';

import '../data/tenant_store.dart';
import 'dues_screen.dart';
import 'maintenance_screen.dart';

class HomeScreen extends StatelessWidget {
  const HomeScreen({super.key, required this.onNavigate});

  /// Switches the bottom navigation tab.
  final void Function(int tab) onNavigate;

  @override
  Widget build(BuildContext context) {
    final s = context.watch<TenantStore>();
    final me = context.watch<Session>().me!;
    final a = s.currentAgreement;
    final scheme = Theme.of(context).colorScheme;
    final next = s.nextDue;
    return RefreshIndicator(
      onRefresh: s.refresh,
      child: ListView(padding: const EdgeInsets.all(16), children: [
        Text(tr('Hello, {name}', {'name': me.name.split(' ').first}), style: Theme.of(context).textTheme.headlineSmall),
        Text(me.orgName, style: Theme.of(context).textTheme.bodyMedium),
        const SizedBox(height: 16),
        Card(
          color: s.outstanding > 0 ? scheme.errorContainer : scheme.primaryContainer,
          child: Padding(
            padding: const EdgeInsets.all(20),
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text(s.outstanding > 0 ? tr('Amount due') : tr('All paid'), style: Theme.of(context).textTheme.titleMedium),
              const SizedBox(height: 4),
              Text(fmtMoney(s.outstanding),
                  style: Theme.of(context).textTheme.displaySmall?.copyWith(fontWeight: FontWeight.bold)),
              if (next != null) ...[
                const SizedBox(height: 8),
                Text(next.status == RentStatus.overdue
                    ? tr('{month} is overdue since {date}',
                        {'month': fmtMonth(next.billingMonth), 'date': fmtDate(next.dueDate)})
                    : tr('Next due: {amount} for {month} on {date}', {
                        'amount': fmtMoney(next.dueAmount),
                        'month': fmtMonth(next.billingMonth),
                        'date': fmtDate(next.dueDate),
                      })),
              ],
              const SizedBox(height: 12),
              FilledButton.tonalIcon(
                onPressed: () => onNavigate(1),
                icon: const Icon(Icons.receipt_long_outlined),
                label: Text(tr('View dues & payments')),
              ),
            ]),
          ),
        ),
        const SizedBox(height: 8),
        if (a == null)
          Card(
            child: ListTile(
              leading: const Icon(Icons.home_outlined),
              title: Text(tr('No active rental')),
              subtitle: Text(tr('Your landlord has not linked a flat to your account yet.')),
            ),
          )
        else
          Card(
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Row(children: [
                  Icon(Icons.home_work_outlined, color: scheme.primary),
                  const SizedBox(width: 8),
                  Expanded(
                    child: Text('${a.propertyName} • ${a.unitNo}', style: Theme.of(context).textTheme.titleMedium),
                  ),
                  StatusChip(a.status),
                ]),
                const Divider(height: 24),
                InfoRow(tr('Monthly total'), fmtMoney(a.totalMonthly)),
                InfoRow(tr('Rent'), fmtMoney(a.monthlyRent)),
                if (a.serviceCharge > 0) InfoRow(tr('Service charge'), fmtMoney(a.serviceCharge)),
                if (a.utilityCharge > 0) InfoRow(tr('Utility charge'), fmtMoney(a.utilityCharge)),
                if (a.otherCharge > 0) InfoRow(tr('Other charge'), fmtMoney(a.otherCharge)),
                InfoRow(tr('Due every month on'), tr('Day {n}', {'n': a.dueDay})),
                InfoRow(tr('Agreement'), '${fmtDate(a.startDate)} → ${fmtDate(a.endDate)}'),
                InfoRow(tr('Security deposit'), fmtMoney(a.securityDeposit)),
              ]),
            ),
          ),
        const SizedBox(height: 8),
        Row(children: [
          Expanded(
            child: Card(
              child: ListTile(
                leading: const Icon(Icons.build_outlined),
                title: Text('${s.openMaintenance}'),
                subtitle: Text(tr('Open requests')),
                onTap: () => onNavigate(2),
              ),
            ),
          ),
          Expanded(
            child: Card(
              child: ListTile(
                leading: const Icon(Icons.notifications_outlined),
                title: Text('${s.unread}'),
                subtitle: Text(tr('New alerts')),
                onTap: () => onNavigate(3),
              ),
            ),
          ),
        ]),
        const SizedBox(height: 8),
        if (a != null)
          OutlinedButton.icon(
            icon: const Icon(Icons.add),
            label: Text(tr('Report a problem')),
            onPressed: () => push(context, const NewRequestScreen()),
          ),
        if (s.payments.isNotEmpty) ...[
          SectionHeader(tr('Latest payment')),
          PaymentCard(payment: s.payments.first),
        ],
      ]),
    );
  }
}
