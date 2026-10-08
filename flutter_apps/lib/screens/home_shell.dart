import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../widgets/common.dart';
import 'agreements_screen.dart';
import 'audit_screen.dart';
import 'dashboard_screen.dart';
import 'dues_screen.dart';
import 'expenses_screen.dart';
import 'license_screen.dart';
import 'maintenance_screen.dart';
import 'notifications_screen.dart';
import 'payments_screen.dart';
import 'profile_screen.dart';
import 'properties_screen.dart';
import 'reports_screen.dart';
import 'rent_screen.dart';
import 'settings_screen.dart';
import 'tenants_screen.dart';
import 'users_screen.dart';

class _Section {
  const _Section(this.title, this.icon, this.roles, this.builder, {this.group});

  final String title;
  final IconData icon;
  final List<UserRole> roles;
  final Widget Function(void Function(String) navigate) builder;
  final String? group;

  /// [title] translated for display (selection stays keyed by the English title).
  String get label => _label(title);
  String? get groupLabel => group == null ? null : _label(group!);
}

/// Display text for a section title or group name.
String _label(String key) => switch (key) {
      'Dashboard' => tr('Dashboard'),
      'Properties' => tr('Properties'),
      'Tenants' => tr('Tenants'),
      'Agreements' => tr('Agreements'),
      'Rent' => tr('Rent'),
      'Payments' => tr('Payments'),
      'Dues' => tr('Dues'),
      'Expenses' => tr('Expenses'),
      'Maintenance' => tr('Maintenance'),
      'Reports' => tr('Reports'),
      'Notifications' => tr('Notifications'),
      'Users' => tr('Users'),
      'Audit Log' => tr('Audit Log'),
      'Settings' => tr('Settings'),
      'Subscription' => tr('Subscription'),
      'Manage' => tr('Manage'),
      'Finance' => tr('Finance'),
      'Operations' => tr('Operations'),
      'Administration' => tr('Administration'),
      _ => key,
    };

const _staff = [UserRole.admin, UserRole.manager];
const _admin = [UserRole.admin];

final _sections = <_Section>[
  _Section('Dashboard', Icons.dashboard_outlined, _staff, (nav) => DashboardScreen(onNavigate: nav)),
  _Section('Properties', Icons.apartment, _staff, (_) => const PropertiesScreen(), group: 'Manage'),
  _Section('Tenants', Icons.people_outline, _staff, (_) => const TenantsScreen(), group: 'Manage'),
  _Section('Agreements', Icons.description_outlined, _staff, (_) => const AgreementsScreen(),
      group: 'Manage'),
  _Section('Rent', Icons.receipt_long_outlined, _staff, (_) => const RentScreen(),
      group: 'Finance'),
  _Section('Payments', Icons.payments_outlined, _staff,
      (_) => const PaymentsScreen(), group: 'Finance'),
  _Section('Dues', Icons.warning_amber_outlined, _staff, (_) => const DuesScreen(), group: 'Finance'),
  _Section('Expenses', Icons.account_balance_wallet_outlined, _staff, (_) => const ExpensesScreen(),
      group: 'Finance'),
  _Section('Maintenance', Icons.build_outlined, _staff,
      (_) => const MaintenanceScreen(), group: 'Operations'),
  _Section('Reports', Icons.bar_chart, _staff, (_) => const ReportsScreen(), group: 'Operations'),
  _Section('Notifications', Icons.notifications_outlined, _staff,
      (_) => const NotificationsScreen(), group: 'Operations'),
  _Section('Users', Icons.manage_accounts_outlined, _admin, (_) => const UsersScreen(),
      group: 'Administration'),
  _Section('Audit Log', Icons.history, _admin, (_) => const AuditScreen(), group: 'Administration'),
  _Section('Settings', Icons.settings_outlined, _admin, (_) => const SettingsScreen(),
      group: 'Administration'),
  _Section('Subscription', Icons.verified_outlined, _admin, (_) => const LicenseScreen(),
      group: 'Administration'),
];

class HomeShell extends StatefulWidget {
  const HomeShell({super.key});

  @override
  State<HomeShell> createState() => _HomeShellState();
}

class _HomeShellState extends State<HomeShell> {
  String _current = '';

  List<_Section> _visible(UserRole role) =>
      _sections.where((s) => s.roles.contains(role)).toList();

  void _navigate(String title) => setState(() => _current = title);

  Future<void> _logout() async {
    if (await confirmDialog(context, tr('Log out'), tr('Do you want to log out?'), confirm: tr('Log out'))) {
      if (mounted) await context.read<Session>().logout();
    }
  }

  @override
  Widget build(BuildContext context) {
    final store = context.watchStore;
    final user = store.currentUser;
    if (user == null) return const SizedBox.shrink();
    final sections = _visible(user.role);
    final section = sections.firstWhere((s) => s.title == _current, orElse: () => sections.first);
    final wide = MediaQuery.sizeOf(context).width >= 1000;

    final nav = _NavList(
      sections: sections,
      selected: section.title,
      unread: store.unreadCount,
      onSelect: (t) {
        _navigate(t);
        if (!wide) Navigator.of(context).maybePop();
      },
    );

    return Scaffold(
      appBar: AppBar(
        title: Text(section.label),
        bottom: store.loading
            ? const PreferredSize(preferredSize: Size.fromHeight(2), child: LinearProgressIndicator(minHeight: 2))
            : null,
        actions: [
          IconButton(
            tooltip: tr('Reload from server'),
            onPressed: store.loading ? null : store.refresh,
            icon: const Icon(Icons.refresh),
          ),
          IconButton(
            tooltip: tr('Notifications'),
            onPressed: () => _navigate('Notifications'),
            icon: Badge(
              isLabelVisible: store.unreadCount > 0,
              label: Text('${store.unreadCount}'),
              child: const Icon(Icons.notifications_outlined),
            ),
          ),
          PopupMenuButton<String>(
            tooltip: tr('Account'),
            icon: CircleAvatar(
              radius: 16,
              child: Text(user.name.isEmpty ? '?' : user.name[0].toUpperCase()),
            ),
            onSelected: (v) {
              switch (v) {
                case 'profile':
                  push(context, const ProfileScreen(standalone: true));
                case 'password':
                  push(context, const ChangePasswordScreen());
                case 'logout':
                  _logout();
              }
            },
            itemBuilder: (_) => [
              PopupMenuItem(
                enabled: false,
                child: Text('${user.name}\n${user.role.label}'),
              ),
              const PopupMenuDivider(),
              PopupMenuItem(value: 'profile', child: Text(tr('My profile'))),
              PopupMenuItem(value: 'password', child: Text(tr('Change password'))),
              PopupMenuItem(value: 'logout', child: Text(tr('Log out'))),
            ],
          ),
          const SizedBox(width: 8),
        ],
      ),
      drawer: wide ? null : Drawer(child: nav),
      body: Column(
        children: [
          const UpdateBanner(),
          const ReadOnlyBanner(),
          if (store.loadError != null)
            MaterialBanner(
              content: Text(store.loaded
                  ? tr('Showing data from {time}. {error}',
                      {'time': fmtDateTime(store.lastLoaded), 'error': store.loadError})
                  : store.loadError!),
              leading: const Icon(Icons.cloud_off),
              actions: [TextButton(onPressed: store.refresh, child: Text(tr('Retry')))],
            ),
          Expanded(
            child: Row(
              children: [
                if (wide) ...[
                  SizedBox(width: 250, child: nav),
                  const VerticalDivider(width: 1),
                ],
                Expanded(
                  child: !store.loaded
                      ? Center(
                          child: store.loading
                              ? const CircularProgressIndicator()
                              : FilledButton.icon(
                                  onPressed: store.refresh,
                                  icon: const Icon(Icons.refresh),
                                  label: Text(tr('Try again'))))
                      : KeyedSubtree(key: ValueKey(section.title), child: section.builder(_navigate)),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _NavList extends StatelessWidget {
  const _NavList({
    required this.sections,
    required this.selected,
    required this.onSelect,
    required this.unread,
  });

  final List<_Section> sections;
  final String selected;
  final ValueChanged<String> onSelect;
  final int unread;

  @override
  Widget build(BuildContext context) {
    final store = context.watchStore;
    final children = <Widget>[
      DrawerHeader(
        margin: EdgeInsets.zero,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          mainAxisAlignment: MainAxisAlignment.end,
          children: [
            Icon(Icons.apartment, size: 36, color: Theme.of(context).colorScheme.primary),
            const SizedBox(height: 8),
            Text(store.settings.orgName, style: Theme.of(context).textTheme.titleMedium),
            Text(store.currentUser?.role.label ?? '', style: Theme.of(context).textTheme.bodySmall),
          ],
        ),
      ),
    ];
    String? lastGroup;
    for (final s in sections) {
      if (s.group != null && s.group != lastGroup) {
        children.add(Padding(
          padding: const EdgeInsets.fromLTRB(16, 12, 16, 4),
          child: Text(s.groupLabel!.toUpperCase(),
              style: Theme.of(context).textTheme.labelSmall?.copyWith(letterSpacing: 1)),
        ));
      }
      lastGroup = s.group ?? lastGroup;
      children.add(ListTile(
        dense: true,
        leading: Icon(s.icon),
        title: Text(s.label),
        selected: s.title == selected,
        trailing: s.title == 'Notifications' && unread > 0
            ? Badge(label: Text('$unread'))
            : null,
        onTap: () => onSelect(s.title),
      ));
    }
    return Material(child: ListView(padding: EdgeInsets.zero, children: children));
  }
}
