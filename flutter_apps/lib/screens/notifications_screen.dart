import 'package:flutter/material.dart';

import '../widgets/common.dart';

class NotificationsScreen extends StatelessWidget {
  const NotificationsScreen({super.key});

  IconData _icon(String title) {
    final t = title.toLowerCase();
    if (t.contains('payment') || t.contains('rent')) return Icons.payments_outlined;
    if (t.contains('agreement')) return Icons.description_outlined;
    if (t.contains('maintenance')) return Icons.build_outlined;
    if (t.contains('password')) return Icons.lock_outline;
    if (t.contains('tenant')) return Icons.person_add_alt;
    return Icons.notifications_outlined;
  }

  @override
  Widget build(BuildContext context) {
    final s = context.watchStore;
    final list = s.myNotifications;
    return Column(
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(16, 12, 16, 0),
          child: Row(children: [
            Text(tr('{unread} unread of {total}', {'unread': s.unreadCount, 'total': list.length})),
            const Spacer(),
            TextButton.icon(
              onPressed: s.unreadCount == 0 ? null : () => runGuarded(context, s.markAllNotificationsRead),
              icon: const Icon(Icons.done_all),
              label: Text(tr('Mark all read')),
            ),
          ]),
        ),
        Expanded(
          child: RefreshIndicator(
            onRefresh: s.refreshNotifications,
            child: list.isEmpty
              ? ListView(children: [
                  const SizedBox(height: 80),
                  EmptyState(message: tr('No notifications'), icon: Icons.notifications_none),
                ])
              : ListView.builder(
                  padding: const EdgeInsets.all(12),
                  itemCount: list.length,
                  itemBuilder: (c, i) {
                    final n = list[i];
                    return Card(
                      color: n.read ? null : Theme.of(context).colorScheme.primaryContainer.withValues(alpha: 0.35),
                      child: ListTile(
                        leading: Icon(_icon(n.title)),
                        title: Text(n.title,
                            style: TextStyle(fontWeight: n.read ? FontWeight.normal : FontWeight.bold)),
                        subtitle: Text('${n.body}\n${fmtDateTime(n.createdAt)}'),
                        isThreeLine: true,
                        trailing: n.read ? null : const Icon(Icons.circle, size: 10),
                        onTap: () => s.markNotificationRead(n),
                      ),
                    );
                  },
                ),
          ),
        ),
      ],
    );
  }
}
