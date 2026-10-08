import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../widgets/common.dart';

class UsersScreen extends StatefulWidget {
  const UsersScreen({super.key});

  @override
  State<UsersScreen> createState() => _UsersScreenState();
}

class _UsersScreenState extends State<UsersScreen> {
  String _q = '';
  UserRole? _role;
  UserStatus? _status;

  @override
  Widget build(BuildContext context) {
    final s = context.watchStore;
    final list = s.users.where((u) {
      if (_role != null && u.role != _role) return false;
      if (_status != null && u.status != _status) return false;
      final q = _q.toLowerCase();
      return q.isEmpty ||
          u.name.toLowerCase().contains(q) ||
          u.username.toLowerCase().contains(q) ||
          u.email.toLowerCase().contains(q) ||
          u.mobile.contains(q);
    }).toList()
      ..sort((a, b) {
        final r = a.role.index.compareTo(b.role.index);
        return r != 0 ? r : a.name.compareTo(b.name);
      });

    return Scaffold(
      floatingActionButton: FloatingActionButton.extended(
        onPressed: () => push(context, const UserFormScreen()),
        icon: const Icon(Icons.person_add),
        label: Text(tr('User')),
      ),
      body: Column(children: [
        FilterBar(
          hint: tr('Search name, username, email…'),
          onSearch: (v) => setState(() => _q = v),
          filters: [
            FilterDropdown<UserRole>(
              label: tr('Role'),
              value: _role,
              items: {for (final r in UserRole.values.where((r) => r != UserRole.vendor)) r: r.label},
              onChanged: (v) => setState(() => _role = v),
            ),
            FilterDropdown<UserStatus>(
              label: tr('Status'),
              value: _status,
              items: {for (final r in UserStatus.values) r: r.label},
              onChanged: (v) => setState(() => _status = v),
            ),
          ],
        ),
        Expanded(
          child: ListView.builder(
            padding: const EdgeInsets.fromLTRB(12, 8, 12, 88),
            itemCount: list.length,
            itemBuilder: (c, i) {
              final u = list[i];
              final extra = switch (u.role) {
                UserRole.manager => u.assignedPropertyIds.isEmpty
                    ? tr('No properties assigned')
                    : u.assignedPropertyIds.map(s.propertyName).join(', '),
                UserRole.tenant => tr('Tenant: {name}', {'name': s.tenantName(u.tenantId)}),
                UserRole.admin => tr('Full access'),
                UserRole.vendor => tr('Vendor'),
              };
              return Card(
                child: ListTile(
                  leading: CircleAvatar(child: Text(u.name.isEmpty ? '?' : u.name[0])),
                  title: Text(u.id == s.currentUser!.id ? tr('{name} (you)', {'name': u.name}) : u.name),
                  subtitle: Text([
                    '${u.username} • ${u.role.label}',
                    extra,
                    if (u.status == UserStatus.blocked) blockedText(u),
                  ].join('\n')),
                  isThreeLine: true,
                  trailing: Row(mainAxisSize: MainAxisSize.min, children: [
                    StatusChip(u.status),
                    PopupMenuButton<String>(
                      onSelected: (v) => _action(context, u, v),
                      itemBuilder: (_) {
                        final me = u.id == s.currentUser!.id;
                        final blocked = u.status == UserStatus.blocked;
                        return [
                          PopupMenuItem(value: 'edit', child: Text(tr('Edit / assign role'))),
                          PopupMenuItem(value: 'reset', child: Text(tr('Reset password'))),
                          if (u.status != UserStatus.active && !blocked)
                            PopupMenuItem(value: 'activate', child: Text(tr('Activate / unlock'))),
                          if (u.status == UserStatus.active && !me)
                            PopupMenuItem(value: 'deactivate', child: Text(tr('Deactivate'))),
                          if (u.status == UserStatus.active && !me)
                            PopupMenuItem(value: 'lock', child: Text(tr('Lock'))),
                          if (!blocked && !me) PopupMenuItem(value: 'block', child: Text(tr('Block access'))),
                          if (blocked && !u.blockedByVendor)
                            PopupMenuItem(value: 'unblock', child: Text(tr('Unblock'))),
                        ];
                      },
                    ),
                  ]),
                  onTap: () => push(context, UserFormScreen(user: u)),
                ),
              );
            },
          ),
        ),
      ]),
    );
  }

  Future<void> _action(BuildContext context, AppUser u, String action) async {
    final s = context.store;
    switch (action) {
      case 'edit':
        push(context, UserFormScreen(user: u));
      case 'activate':
        runGuarded(context, () => s.setUserStatus(u, UserStatus.active), success: tr('User activated'));
      case 'deactivate':
        runGuarded(context, () => s.setUserStatus(u, UserStatus.inactive), success: tr('User deactivated'));
      case 'lock':
        runGuarded(context, () => s.setUserStatus(u, UserStatus.locked), success: tr('User locked'));
      case 'block':
        final reason = await promptText(context, tr('Block {name}?', {'name': u.name}),
            label: tr('Reason (the user will see it when signing in)'));
        if (reason == null || !context.mounted) return;
        runGuarded(context, () => s.blockUser(u, reason), success: tr('User blocked and signed out'));
      case 'unblock':
        if (!await confirmDialog(context, tr('Unblock'), tr('Let {name} sign in again?', {'name': u.name}))) return;
        if (!context.mounted) return;
        runGuarded(context, () => s.unblockUser(u), success: tr('User unblocked'));
      case 'reset':
        if (!await confirmDialog(context, tr('Reset password'),
            tr('Generate a temporary password for {name}? They must change it at next login.', {'name': u.name}))) {
          return;
        }
        if (!context.mounted) return;
        var pw = '';
        if (await runGuarded(context, () async => pw = await s.adminResetPassword(u)) && context.mounted) {
          await showDialog(
            context: context,
            builder: (c) => AlertDialog(
              title: Text(tr('Temporary password')),
              content: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
                Text(tr('Share this temporary password with {name} ({username}):', {'name': u.name, 'username': u.username})),
                const SizedBox(height: 12),
                SelectableText(pw, style: Theme.of(c).textTheme.headlineSmall),
              ]),
              actions: [
                TextButton.icon(
                  icon: const Icon(Icons.copy),
                  label: Text(tr('Copy')),
                  onPressed: () => Clipboard.setData(ClipboardData(text: pw)),
                ),
                FilledButton(onPressed: () => Navigator.pop(c), child: Text(tr('Done'))),
              ],
            ),
          );
        }
    }
  }
}

class UserFormScreen extends StatefulWidget {
  const UserFormScreen({super.key, this.user});

  final AppUser? user;

  @override
  State<UserFormScreen> createState() => _UserFormScreenState();
}

class _UserFormScreenState extends State<UserFormScreen> {
  final _key = GlobalKey<FormState>();
  late final u = widget.user;
  late final _name = TextEditingController(text: u?.name);
  late final _username = TextEditingController(text: u?.username);
  late final _email = TextEditingController(text: u?.email);
  late final _mobile = TextEditingController(text: u?.mobile);
  final _password = TextEditingController();
  late UserRole _role = u?.role ?? UserRole.manager;
  late final Set<String> _props = {...?u?.assignedPropertyIds};
  late String? _tenantId = u?.tenantId;

  Future<void> _save() async {
    final s = context.store;
    if (_role == UserRole.tenant && _tenantId == null) {
      showSnack(context, tr('Select the tenant record for this login'), error: true);
      return;
    }
    final target = u == null
        ? AppUser(id: '', name: '', username: '', role: _role)
        : (AppUser(id: u!.id, name: u!.name, username: u!.username, role: u!.role)
          ..status = u!.status);
    target
      ..name = _name.text.trim()
      ..username = _username.text.trim()
      ..email = _email.text.trim()
      ..mobile = _mobile.text.trim()
      ..role = _role
      ..assignedPropertyIds = _role == UserRole.manager ? _props.toList() : []
      ..tenantId = _role == UserRole.tenant ? _tenantId : null;
    final ok = await runGuarded(context, () => s.saveUser(target, password: u == null ? _password.text : null),
        success: tr('User saved'));
    if (ok && mounted) Navigator.pop(context);
  }

  @override
  Widget build(BuildContext context) {
    final s = context.watchStore;
    final takenTenantIds = s.users.where((x) => x.id != u?.id && x.tenantId != null).map((x) => x.tenantId);
    final tenantItems = {
      for (final t in s.tenants.where((t) => !takenTenantIds.contains(t.id))) t.id: '${t.name} (${t.code})'
    };
    return FormPage(
      title: u == null ? tr('New user') : tr('Edit user'),
      formKey: _key,
      onSave: _save,
      children: [
        FormGrid(children: [
          AppTextField(controller: _name, label: tr('Name *'), validator: requiredValidator),
          AppTextField(controller: _username, label: tr('Username *'), latin: true, validator: requiredValidator),
          AppTextField(controller: _email, label: tr('Email'), validator: optionalEmailValidator),
          AppTextField(controller: _mobile, label: tr('Mobile'), keyboardType: TextInputType.phone),
          AppDropdown<UserRole>(
            label: tr('Role *'),
            value: _role,
            items: {for (final r in UserRole.values) r: r.label},
            onChanged: (v) => setState(() => _role = v!),
          ),
          if (u == null)
            AppTextField(
              controller: _password,
              label: tr('Initial password *'),
              obscure: true,
              validator: validatePassword,
            ),
          if (_role == UserRole.tenant)
            AppDropdown<String>(
              label: tr('Linked tenant record *'),
              value: _tenantId,
              items: tenantItems,
              onChanged: (v) => setState(() => _tenantId = v),
            ),
        ]),
        if (_role == UserRole.manager) ...[
          SectionHeader(tr('Assigned properties')),
          if (s.properties.isEmpty) Text(tr('No properties yet')),
          for (final p in s.properties)
            CheckboxListTile(
              value: _props.contains(p.id),
              title: Text(p.name),
              subtitle: Text('${p.code} • ${p.city}'),
              onChanged: (v) => setState(() => v == true ? _props.add(p.id) : _props.remove(p.id)),
            ),
        ],
        if (u != null) ...[
          const SizedBox(height: 12),
          Text(tr('Status: {status} • Use the list menu to activate, lock or reset password.', {'status': u!.status.label}),
              style: Theme.of(context).textTheme.bodySmall),
          if (u!.status == UserStatus.blocked)
            Text(blockedText(u!), style: TextStyle(color: Theme.of(context).colorScheme.error)),
        ],
      ],
    );
  }
}
