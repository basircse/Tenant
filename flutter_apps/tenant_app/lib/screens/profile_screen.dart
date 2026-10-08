import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'package:tms_core/tms_core.dart';

import '../data/tenant_store.dart';

class AlertsScreen extends StatelessWidget {
  const AlertsScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final s = context.watch<TenantStore>();
    final list = s.notifications;
    return RefreshIndicator(
      onRefresh: s.refreshNotifications,
      child: list.isEmpty
          ? ListView(children: [
              const SizedBox(height: 80),
              EmptyState(message: tr('No alerts'), icon: Icons.notifications_none),
            ])
          : ListView(padding: const EdgeInsets.all(12), children: [
              if (s.unread > 0)
                Align(
                  alignment: Alignment.centerRight,
                  child: TextButton.icon(
                    icon: const Icon(Icons.done_all),
                    label: Text(tr('Mark all read')),
                    onPressed: () => runApi(context, s.markAllRead, showProgress: false),
                  ),
                ),
              for (final n in list)
                Card(
                  color: n.read ? null : Theme.of(context).colorScheme.primaryContainer.withValues(alpha: 0.35),
                  child: ListTile(
                    title: Text(n.title, style: TextStyle(fontWeight: n.read ? null : FontWeight.bold)),
                    subtitle: Text('${n.body}\n${fmtDateTime(n.createdAt)}'),
                    isThreeLine: true,
                    onTap: () => s.markRead(n),
                  ),
                ),
            ]),
    );
  }
}

class ProfileScreen extends StatelessWidget {
  const ProfileScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final s = context.watch<TenantStore>();
    final session = context.watch<Session>();
    final t = s.profile;
    final me = session.me!;
    return RefreshIndicator(
      onRefresh: s.refresh,
      child: ListView(padding: const EdgeInsets.all(16), children: [
        Row(children: [
          CircleAvatar(radius: 32, child: Text(t?.initials ?? '?', style: const TextStyle(fontSize: 22))),
          const SizedBox(width: 16),
          Expanded(
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text(t?.name ?? me.name, style: Theme.of(context).textTheme.titleLarge),
              Text('${t?.code ?? ''} • ${me.orgName}'),
            ]),
          ),
        ]),
        const SizedBox(height: 12),
        if (t != null)
          InfoCard(title: tr('My details'), children: [
            InfoRow(tr('Mobile'), t.mobile),
            InfoRow(tr('Email'), t.email),
            InfoRow(tr('NID / Passport'), t.nid),
            InfoRow(tr('Date of birth'), fmtDate(t.dateOfBirth)),
            InfoRow(tr('Address'), t.presentAddress),
            InfoRow(tr('Emergency contact'), t.emergencyContact),
            InfoRow(tr('Occupation'), t.occupation),
            Padding(
              padding: const EdgeInsets.only(top: 8),
              child: Text(tr('To change your details, please contact your landlord.')),
            ),
          ]),
        InfoCard(
          title: tr('Agreements'),
          children: s.agreements.isEmpty
              ? [Text(tr('No agreements'))]
              : [
                  for (final a in s.agreements)
                    ListTile(
                      contentPadding: EdgeInsets.zero,
                      title: Text('${a.propertyName} • ${a.unitNo}'),
                      subtitle: Text('${a.code} • ${fmtDate(a.startDate)} → ${fmtDate(a.endDate)}\n'
                          '${tr('{amount}/month', {'amount': fmtMoney(a.totalMonthly)})}'),
                      isThreeLine: true,
                      trailing: StatusChip(a.status),
                    ),
                ],
        ),
        if (s.documents.isNotEmpty)
          InfoCard(title: tr('My documents'), children: [
            for (final d in s.documents)
              ListTile(
                contentPadding: EdgeInsets.zero,
                leading: const Icon(Icons.insert_drive_file_outlined),
                title: Text(d.name),
                subtitle: Text('${d.docType} • ${fmtDate(d.addedAt)}'),
                trailing: d.hasFile ? const Icon(Icons.download_outlined) : null,
                onTap: d.hasFile ? () => downloadAndOpen(context, () => s.document(d)) : null,
              ),
          ]),
        InfoCard(title: tr('App'), children: [
          InfoRow(tr('Signed in as'), me.username),
          if (session.appVersion.isNotEmpty) InfoRow(tr('Version'), session.appVersion),
        ]),
        const SizedBox(height: 8),
        const Card(child: LanguageTile()),
        const SizedBox(height: 8),
        Wrap(spacing: 8, runSpacing: 8, children: [
          OutlinedButton.icon(
            icon: const Icon(Icons.lock_outline),
            label: Text(tr('Change password')),
            onPressed: () => push(context, const ChangePasswordScreen()),
          ),
          OutlinedButton.icon(
            icon: const Icon(Icons.logout),
            label: Text(tr('Sign out')),
            onPressed: () async {
              if (await confirmDialog(context, tr('Sign out'), tr('Do you want to sign out?'), confirm: tr('Sign out'))) {
                await session.logout();
              }
            },
          ),
        ]),
      ]),
    );
  }
}
