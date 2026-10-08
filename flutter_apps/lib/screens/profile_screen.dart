import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../widgets/common.dart';

class ProfileScreen extends StatelessWidget {
  const ProfileScreen({super.key, this.standalone = false});

  /// True when pushed as its own page (adds an app bar).
  final bool standalone;

  @override
  Widget build(BuildContext context) {
    final s = context.watchStore;
    final session = context.watch<Session>();
    final me = session.me;
    if (me == null) return const SizedBox.shrink();
    final u = s.userById(me.userId);
    final body = ListView(
      padding: const EdgeInsets.all(16),
      children: [
        Row(children: [
          CircleAvatar(
              radius: 32,
              child: Text(me.name.isEmpty ? '?' : me.name[0], style: const TextStyle(fontSize: 24))),
          const SizedBox(width: 16),
          Expanded(
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text(me.name, style: Theme.of(context).textTheme.titleLarge),
              Text(me.role.label),
            ]),
          ),
        ]),
        const SizedBox(height: 16),
        InfoCard(title: tr('Account'), children: [
          InfoRow(tr('Username'), me.username),
          InfoRow(tr('Organisation'), me.orgName),
          if (u != null) InfoRow(tr('Email'), u.email),
          if (u != null) InfoRow(tr('Mobile'), u.mobile),
          if (u != null && u.assignedPropertyIds.isNotEmpty)
            InfoRow(tr('Assigned properties'), u.assignedPropertyIds.map(s.propertyName).join(', ')),
          InfoRow(tr('This device'), session.device.name),
          if (session.appVersion.isNotEmpty) InfoRow(tr('App version'), session.appVersion),
        ]),
        const SizedBox(height: 8),
        const Card(child: LanguageTile()),
        const SizedBox(height: 8),
        Align(
          alignment: Alignment.centerLeft,
          child: OutlinedButton.icon(
            icon: const Icon(Icons.lock_outline),
            label: Text(tr('Change password')),
            onPressed: () => push(context, const ChangePasswordScreen()),
          ),
        ),
      ],
    );
    if (!standalone) return body;
    return Scaffold(appBar: AppBar(title: Text(tr('My profile'))), body: body);
  }
}
