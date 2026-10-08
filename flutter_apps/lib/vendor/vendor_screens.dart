import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../widgets/common.dart';

// Vendor console: the software provider approves landlord registrations,
// manages subscriptions (expiry, plan, unit and device limits) and devices.

class OrgSummary {
  OrgSummary.fromApi(Json j)
      : id = str(j['id']),
        name = str(j['name']),
        contactName = str(j['contactName']),
        phone = str(j['phone']),
        email = str(j['email']),
        state = apiEnum(LicenseState.values, j['state'], LicenseState.pending),
        plan = j['plan'],
        expiresOn = parseDay(j['expiresOn']),
        daysLeft = (j['daysLeft'] as num?)?.toInt(),
        maxUnits = (j['maxUnits'] as num?)?.toInt(),
        unitsUsed = (j['unitsUsed'] as num?)?.toInt() ?? 0,
        maxDevices = (j['maxDevices'] as num?)?.toInt(),
        devicesUsed = (j['devicesUsed'] as num?)?.toInt() ?? 0,
        pendingDevices = (j['pendingDevices'] as num?)?.toInt() ?? 0,
        registeredAt = parseDate(j['registeredAt']),
        approvedAt = parseDate(j['approvedAt']);

  final String id;
  final String name;
  final String contactName;
  final String phone;
  final String email;
  final LicenseState state;
  final String? plan;
  final DateTime? expiresOn;
  final int? daysLeft;
  final int? maxUnits;
  final int unitsUsed;
  final int? maxDevices;
  final int devicesUsed;
  final int pendingDevices;
  final DateTime? registeredAt;
  final DateTime? approvedAt;

  String get limitText => tr('Units {used}/{max} • Devices {devicesUsed}/{devicesMax}', {
        'used': unitsUsed,
        'max': maxUnits ?? '∞',
        'devicesUsed': devicesUsed,
        'devicesMax': maxDevices ?? '∞',
      });

  String get expiryText {
    if (expiresOn == null) return state == LicenseState.active ? tr('No expiry') : '-';
    final d = daysLeft;
    final date = fmtDate(expiresOn);
    if (d == null) return date;
    return d >= 0
        ? tr('{date} ({n} days left)', {'date': date, 'n': d})
        : tr('{date} ({n} days ago)', {'date': date, 'n': -d});
  }
}

class OrgDetail {
  OrgDetail.fromApi(Json j, {this.users = const []})
      : org = OrgSummary.fromApi(asJson(j['organization'])),
        address = str(j['address']),
        licenseNote = str(j['licenseNote']),
        admins = asJsonList(j['admins']),
        devices = asJsonList(j['devices']).map(DeviceInfoView.fromApi).toList(),
        history = asJsonList(j['history']);

  final OrgSummary org;
  final String address;
  final String licenseNote;
  final List<Json> admins;
  final List<DeviceInfoView> devices;
  final List<Json> history;

  /// Every login of the organisation (staff and tenants), from /api/vendor/users.
  final List<AppUser> users;
}

ApiClient _api(BuildContext context) => context.read<Session>().api;

class VendorShell extends StatefulWidget {
  const VendorShell({super.key});

  @override
  State<VendorShell> createState() => _VendorShellState();
}

class _VendorShellState extends State<VendorShell> {
  int _tab = 0;

  @override
  Widget build(BuildContext context) {
    final pages = [
      VendorOverview(onOpenList: (state) => setState(() => _tab = 1)),
      const OrganizationsScreen(),
      const VendorNotificationsScreen(),
      const VendorAccountScreen(),
    ];
    return Scaffold(
      appBar: AppBar(title: Text([tr('Overview'), tr('Landlords'), tr('Notifications'), tr('Account')][_tab])),
      body: pages[_tab],
      bottomNavigationBar: NavigationBar(
        selectedIndex: _tab,
        onDestinationSelected: (i) => setState(() => _tab = i),
        destinations: [
          NavigationDestination(icon: const Icon(Icons.dashboard_outlined), label: tr('Overview')),
          NavigationDestination(icon: const Icon(Icons.business_outlined), label: tr('Landlords')),
          NavigationDestination(icon: const Icon(Icons.notifications_outlined), label: tr('Alerts')),
          NavigationDestination(icon: const Icon(Icons.person_outline), label: tr('Account')),
        ],
      ),
    );
  }
}

/// Loads JSON from the API and rebuilds; pull to refresh.
class _Loader<T> extends StatefulWidget {
  const _Loader({super.key, required this.load, required this.builder});

  final Future<T> Function() load;
  final Widget Function(BuildContext context, T data, Future<void> Function() reload) builder;

  @override
  State<_Loader<T>> createState() => _LoaderState<T>();
}

class _LoaderState<T> extends State<_Loader<T>> {
  T? _data;
  String? _error;

  @override
  void initState() {
    super.initState();
    _reload();
  }

  Future<void> _reload() async {
    try {
      final d = await widget.load();
      if (mounted) {
        setState(() {
          _data = d;
          _error = null;
        });
      }
    } on ApiException catch (e) {
      if (mounted) setState(() => _error = e.message);
    }
  }

  @override
  Widget build(BuildContext context) {
    final d = _data;
    if (d == null) {
      return Center(
        child: _error == null
            ? const CircularProgressIndicator()
            : Column(mainAxisSize: MainAxisSize.min, children: [
                Text(_error!),
                const SizedBox(height: 12),
                FilledButton(onPressed: _reload, child: Text(tr('Retry'))),
              ]),
      );
    }
    return RefreshIndicator(onRefresh: _reload, child: widget.builder(context, d, _reload));
  }
}

class VendorOverview extends StatelessWidget {
  const VendorOverview({super.key, required this.onOpenList});

  final void Function(LicenseState? state) onOpenList;

  @override
  Widget build(BuildContext context) {
    final api = _api(context);
    return _Loader<(Json, List<OrgSummary>)>(
      load: () async {
        final results = await Future.wait([
          api.get('/api/vendor/summary'),
          api.get('/api/vendor/organizations'),
        ]);
        return (asJson(results[0]), asJsonList(results[1]).map(OrgSummary.fromApi).toList());
      },
      builder: (context, data, reload) {
        final (sum, orgs) = data;
        int n(String k) => (sum[k] as num?)?.toInt() ?? 0;
        final attention = orgs
            .where((o) =>
                o.state == LicenseState.pending ||
                o.state == LicenseState.grace ||
                o.pendingDevices > 0 ||
                (o.state == LicenseState.active && o.daysLeft != null && o.daysLeft! <= 30))
            .toList();
        return ListView(padding: const EdgeInsets.all(16), children: [
          ResponsiveGrid(children: [
            StatCard(label: tr('Waiting for approval'), value: '${n('pending')}', icon: Icons.hourglass_top),
            StatCard(label: tr('Active'), value: '${n('active')}', icon: Icons.verified_outlined),
            StatCard(label: tr('Expiring in 30 days'), value: '${n('expiringIn30Days')}', icon: Icons.event),
            StatCard(label: tr('In grace period'), value: '${n('inGrace')}', icon: Icons.lock_clock),
            StatCard(label: tr('Expired'), value: '${n('expired')}', icon: Icons.event_busy),
            StatCard(label: tr('Suspended'), value: '${n('suspended')}', icon: Icons.pause_circle_outline),
            StatCard(label: tr('Devices waiting'), value: '${n('pendingDevices')}', icon: Icons.phonelink_setup),
          ]),
          SectionHeader(tr('Needs attention')),
          if (attention.isEmpty) Text(tr('Nothing needs your attention.')),
          for (final o in attention) _OrgTile(org: o, onChanged: reload),
        ]);
      },
    );
  }
}

class _OrgTile extends StatelessWidget {
  const _OrgTile({required this.org, required this.onChanged});

  final OrgSummary org;
  final Future<void> Function() onChanged;

  @override
  Widget build(BuildContext context) {
    final o = org;
    return Card(
      child: ListTile(
        title: Text(o.name),
        subtitle: Text([
          '${o.contactName} • ${o.phone}',
          if (o.state == LicenseState.pending)
            tr('Registered {time}', {'time': fmtDateTime(o.registeredAt)})
          else
            '${o.plan ?? tr('No plan')} • ${o.expiryText}',
          o.pendingDevices > 0
              ? '${o.limitText} • ${tr('Devices waiting: {n}', {'n': o.pendingDevices})}'
              : o.limitText,
        ].join('\n')),
        isThreeLine: true,
        trailing: StatusChip(o.state),
        onTap: () async {
          await push(context, OrganizationDetailScreen(orgId: o.id));
          await onChanged();
        },
      ),
    );
  }
}

class OrganizationsScreen extends StatefulWidget {
  const OrganizationsScreen({super.key});

  @override
  State<OrganizationsScreen> createState() => _OrganizationsScreenState();
}

class _OrganizationsScreenState extends State<OrganizationsScreen> {
  LicenseState? _state;
  String _q = '';

  @override
  Widget build(BuildContext context) {
    final api = _api(context);
    return Column(children: [
      FilterBar(
        hint: tr('Search name, phone, email…'),
        onSearch: (v) => setState(() => _q = v),
        filters: [
          FilterDropdown<LicenseState>(
            label: tr('State'),
            value: _state,
            items: {for (final s in LicenseState.values) s: s.label},
            onChanged: (v) => setState(() => _state = v),
          ),
        ],
      ),
      Expanded(
        child: _Loader<List<OrgSummary>>(
          key: ValueKey('$_state|$_q'),
          load: () async => asJsonList(await api.get('/api/vendor/organizations',
                  query: {'state': _state == null ? null : apiName(_state!), 'q': _q}))
              .map(OrgSummary.fromApi)
              .toList(),
          builder: (context, orgs, reload) => orgs.isEmpty
              ? ListView(children: [const SizedBox(height: 80), EmptyState(message: tr('No landlords found'))])
              : ListView(padding: const EdgeInsets.all(12), children: [
                  for (final o in orgs) _OrgTile(org: o, onChanged: reload),
                ]),
        ),
      ),
    ]);
  }
}

class OrganizationDetailScreen extends StatefulWidget {
  const OrganizationDetailScreen({super.key, required this.orgId});

  final String orgId;

  @override
  State<OrganizationDetailScreen> createState() => _OrganizationDetailScreenState();
}

class _OrganizationDetailScreenState extends State<OrganizationDetailScreen> {
  int _version = 0;

  String get _base => '/api/vendor/organizations/${widget.orgId}';

  Future<void> _do(Future<dynamic> Function(ApiClient api) call, String success) async {
    final api = _api(context);
    if (await runApi(context, () => call(api), success: success) && mounted) {
      setState(() => _version++);
    }
  }

  Future<void> _approve(OrgDetail d, {required bool approve}) async {
    final r = await showDialog<Json>(
      context: context,
      builder: (_) => _LicenseDialog(
        title: approve ? tr('Approve {name}', {'name': d.org.name}) : tr('Change limits'),
        org: d.org,
        askExpiry: approve,
        askLimits: true,
      ),
    );
    if (r == null) return;
    if (approve) {
      await _do((api) => api.post('$_base/approve', r), tr('Approved'));
    } else {
      await _do((api) => api.put('$_base/limits', r), tr('Limits updated'));
    }
  }

  Future<void> _extend(OrgDetail d) async {
    final r = await showDialog<Json>(
      context: context,
      builder: (_) => _LicenseDialog(title: tr('Extend subscription'), org: d.org, askExpiry: true, askLimits: false),
    );
    if (r == null) return;
    await _do((api) => api.post('$_base/extend', {'expiresOn': r['expiresOn'], 'note': r['note']}),
        tr('Subscription extended'));
  }

  Future<void> _reason(String title, String path, String success, {String key = 'reason', bool required = true}) async {
    final text = await promptText(context, title,
        label: required ? tr('Reason (the landlord will see it)') : tr('Note (optional)'), required: required);
    if (text == null) return;
    await _do((api) => api.post('$_base/$path', {key: text}), success);
  }

  /// Blocks or unblocks any login of the organisation. Only the vendor can lift a block they placed.
  Future<void> _blockUser(AppUser u, {required bool block}) async {
    final path = '/api/vendor/users/${u.id}/${block ? 'block' : 'unblock'}';
    if (block) {
      final reason = await promptText(context, tr('Block {name}?', {'name': u.name}),
          label: tr('Reason (the user will see it when signing in)'));
      if (reason == null) return;
      await _do((api) => api.post(path, {'reason': reason}), tr('User blocked and signed out'));
    } else {
      if (!await confirmDialog(context, tr('Unblock'), tr('Let {name} sign in again?', {'name': u.name}))) return;
      await _do((api) => api.post(path), tr('User unblocked'));
    }
  }

  @override
  Widget build(BuildContext context) {
    final api = _api(context);
    return Scaffold(
      appBar: AppBar(title: Text(tr('Landlord'))),
      body: _Loader<OrgDetail>(
        key: ValueKey(_version),
        load: () async {
          final res = await Future.wait([api.get(_base), api.get('/api/vendor/users', query: {'org': widget.orgId})]);
          return OrgDetail.fromApi(asJson(res[0]),
              users: asJsonList(res[1]).map((j) => AppUser.fromApi(asJson(j['user']))).toList());
        },
        builder: (context, d, reload) {
          final o = d.org;
          final s = o.state;
          return ListView(padding: const EdgeInsets.all(16), children: [
            Row(children: [
              Expanded(child: Text(o.name, style: Theme.of(context).textTheme.headlineSmall)),
              StatusChip(s),
            ]),
            const SizedBox(height: 12),
            Wrap(spacing: 8, runSpacing: 8, children: [
              if (s == LicenseState.pending) ...[
                FilledButton.icon(
                    onPressed: () => _approve(d, approve: true),
                    icon: const Icon(Icons.check),
                    label: Text(tr('Approve'))),
                OutlinedButton.icon(
                    onPressed: () => _reason(tr('Reject registration'), 'reject', tr('Rejected')),
                    icon: const Icon(Icons.close),
                    label: Text(tr('Reject'))),
              ],
              if (s == LicenseState.rejected)
                FilledButton.icon(
                    onPressed: () => _approve(d, approve: true),
                    icon: const Icon(Icons.check),
                    label: Text(tr('Approve anyway'))),
              if (s == LicenseState.active || s == LicenseState.grace || s == LicenseState.expired) ...[
                FilledButton.icon(
                    onPressed: () => _extend(d), icon: const Icon(Icons.update), label: Text(tr('Extend'))),
                OutlinedButton.icon(
                    onPressed: () => _approve(d, approve: false),
                    icon: const Icon(Icons.tune),
                    label: Text(tr('Limits & plan'))),
                OutlinedButton.icon(
                    onPressed: () => _reason(tr('Suspend {name}', {'name': o.name}), 'suspend', tr('Suspended')),
                    icon: const Icon(Icons.pause),
                    label: Text(tr('Suspend'))),
              ],
              if (s == LicenseState.suspended)
                FilledButton.icon(
                    onPressed: () =>
                        _reason(tr('Reactivate {name}', {'name': o.name}), 'reactivate', tr('Reactivated'),
                            key: 'note', required: false),
                    icon: const Icon(Icons.play_arrow),
                    label: Text(tr('Reactivate'))),
            ]),
            InfoCard(title: tr('Subscription'), children: [
              InfoRow(tr('State'), s.label),
              InfoRow(tr('Plan'), o.plan ?? '-'),
              InfoRow(tr('Expires'), o.expiryText),
              InfoRow(tr('Units'), tr('{used} of {max}', {'used': o.unitsUsed, 'max': o.maxUnits ?? tr('unlimited')})),
              InfoRow(tr('Admin devices'),
                  tr('{used} of {max}', {'used': o.devicesUsed, 'max': o.maxDevices ?? tr('unlimited')})),
              InfoRow(tr('Registered'), fmtDateTime(o.registeredAt)),
              InfoRow(tr('Approved'), fmtDateTime(o.approvedAt)),
              if (d.licenseNote.isNotEmpty) InfoRow(tr('Note'), d.licenseNote),
            ]),
            InfoCard(title: tr('Contact'), children: [
              InfoRow(tr('Name'), o.contactName),
              InfoRow(tr('Phone'), o.phone),
              InfoRow(tr('Email'), o.email),
              InfoRow(tr('Address'), d.address),
              for (final a in d.admins)
                InfoRow(tr('Admin login'), '${a['name']} (${a['username']}) • ${a['status']}'),
            ]),
            InfoCard(
              title: tr('Users ({n})', {'n': d.users.length}),
              children: [
                for (final u in d.users)
                  ListTile(
                    contentPadding: EdgeInsets.zero,
                    leading: Icon(u.role == UserRole.tenant ? Icons.person_outline : Icons.badge_outlined),
                    title: Text(u.name),
                    subtitle: Text([
                      '${u.username} • ${u.role.label}',
                      if (u.status == UserStatus.blocked) blockedText(u),
                    ].join('\n')),
                    trailing: Row(mainAxisSize: MainAxisSize.min, children: [
                      StatusChip(u.status),
                      PopupMenuButton<String>(
                        onSelected: (v) => _blockUser(u, block: v == 'block'),
                        itemBuilder: (_) => [
                          if (u.status != UserStatus.blocked)
                            PopupMenuItem(value: 'block', child: Text(tr('Block access'))),
                          if (u.status == UserStatus.blocked)
                            PopupMenuItem(value: 'unblock', child: Text(tr('Unblock'))),
                        ],
                      ),
                    ]),
                  ),
              ],
            ),
            InfoCard(
              title: tr('Devices ({n})', {'n': d.devices.length}),
              children: d.devices.isEmpty
                  ? [Text(tr('No devices yet'))]
                  : [
                      for (final dev in d.devices)
                        ListTile(
                          contentPadding: EdgeInsets.zero,
                          leading: Icon(dev.app == 'TENANT' ? Icons.person_outline : Icons.admin_panel_settings),
                          title: Text(dev.name),
                          subtitle: Text(tr('{app} app • {platform} • last seen {time}', {
                            'app': dev.app.toLowerCase(),
                            'platform': dev.platform,
                            'time': fmtDateTime(dev.lastSeenAt),
                          })),
                          trailing: Row(mainAxisSize: MainAxisSize.min, children: [
                            StatusChip(dev.status),
                            PopupMenuButton<String>(
                              onSelected: (v) => _do((api) => api.post('/api/vendor/devices/${dev.id}/$v'),
                                  v == 'approve' ? tr('Device approved') : tr('Device blocked')),
                              itemBuilder: (_) => [
                                if (dev.status != DeviceStatus.approved)
                                  PopupMenuItem(value: 'approve', child: Text(tr('Approve'))),
                                if (dev.status != DeviceStatus.blocked)
                                  PopupMenuItem(value: 'block', child: Text(tr('Block'))),
                              ],
                            ),
                          ]),
                        ),
                    ],
            ),
            InfoCard(title: tr('History'), children: [
              for (final h in d.history)
                ListTile(
                  contentPadding: EdgeInsets.zero,
                  dense: true,
                  title: Text(str(h['action'])),
                  subtitle: Text([
                    if (str(h['details']).isNotEmpty) str(h['details']),
                    if (h['expiresAfter'] != null || h['expiresBefore'] != null)
                      tr('Expiry {from} → {to}', {
                        'from': fmtDate(parseDay(h['expiresBefore'])),
                        'to': fmtDate(parseDay(h['expiresAfter'])),
                      }),
                    '${str(h['actorName'])} • ${fmtDateTime(parseDate(h['at']))}',
                  ].join('\n')),
                ),
            ]),
          ]);
        },
      ),
    );
  }
}

/// Collects expiry / plan / limits for approve, extend and limit changes.
class _LicenseDialog extends StatefulWidget {
  const _LicenseDialog({required this.title, required this.org, required this.askExpiry, required this.askLimits});

  final String title;
  final OrgSummary org;
  final bool askExpiry;
  final bool askLimits;

  @override
  State<_LicenseDialog> createState() => _LicenseDialogState();
}

class _LicenseDialogState extends State<_LicenseDialog> {
  final _key = GlobalKey<FormState>();
  late DateTime? _expires = _defaultExpiry();
  late bool _noExpiry = false;
  late final _plan = TextEditingController(text: widget.org.plan ?? 'Standard');
  late final _units = TextEditingController(text: '${widget.org.maxUnits ?? ''}');
  late final _devices = TextEditingController(text: '${widget.org.maxDevices ?? 2}');
  final _note = TextEditingController();

  DateTime _defaultExpiry() {
    final base = widget.org.expiresOn != null && widget.org.expiresOn!.isAfter(today()) ? widget.org.expiresOn! : today();
    return DateTime(base.year + 1, base.month, base.day);
  }

  String? _limit(String? v) {
    if (v == null || v.trim().isEmpty) return null;
    final n = int.tryParse(latinDigits(v.trim()));
    return n == null || n < 1 ? tr('Whole number ≥ 1, or empty for unlimited') : null;
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: Text(widget.title),
      content: Form(
        key: _key,
        child: SizedBox(
          width: 420,
          child: SingleChildScrollView(
            child: Column(mainAxisSize: MainAxisSize.min, children: [
              if (widget.askExpiry) ...[
                CheckboxListTile(
                  contentPadding: EdgeInsets.zero,
                  value: _noExpiry,
                  title: Text(tr('No expiry')),
                  onChanged: (v) => setState(() => _noExpiry = v ?? false),
                ),
                if (!_noExpiry)
                  DateField(
                    label: tr('Subscription valid until *'),
                    value: _expires,
                    firstDate: today(),
                    lastDate: DateTime(today().year + 20),
                    onChanged: (d) => setState(() => _expires = d),
                  ),
                const SizedBox(height: 12),
              ],
              if (widget.askLimits) ...[
                AppTextField(controller: _plan, label: tr('Plan name')),
                const SizedBox(height: 12),
                AppTextField(
                    controller: _units,
                    label: tr('Max units (empty = unlimited)'),
                    keyboardType: TextInputType.number,
                    validator: _limit),
                const SizedBox(height: 12),
                AppTextField(
                    controller: _devices,
                    label: tr('Max admin devices (empty = unlimited)'),
                    keyboardType: TextInputType.number,
                    validator: _limit),
                const SizedBox(height: 12),
              ],
              AppTextField(controller: _note, label: tr('Note (e.g. payment reference)')),
            ]),
          ),
        ),
      ),
      actions: [
        TextButton(onPressed: () => Navigator.pop(context), child: Text(tr('Cancel'))),
        FilledButton(
          onPressed: () {
            if (!_key.currentState!.validate()) return;
            if (widget.askExpiry && !_noExpiry && _expires == null) return;
            int? n(TextEditingController c) => c.text.trim().isEmpty ? null : int.parse(latinDigits(c.text.trim()));
            Navigator.pop(context, <String, dynamic>{
              if (widget.askExpiry) 'expiresOn': _noExpiry ? null : isoDay(_expires!),
              if (widget.askLimits) ...{
                'plan': _plan.text.trim(),
                'maxUnits': n(_units),
                'maxDevices': n(_devices),
              },
              'note': _note.text.trim(),
            });
          },
          child: Text(tr('Save')),
        ),
      ],
    );
  }
}

class VendorNotificationsScreen extends StatelessWidget {
  const VendorNotificationsScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final api = _api(context);
    return _Loader<List<AppNotification>>(
      load: () async => asJsonList(asJson(await api.get('/api/notifications'))['items'])
          .map(AppNotification.fromApi)
          .toList(),
      builder: (context, list, reload) => list.isEmpty
          ? ListView(children: [const SizedBox(height: 80), EmptyState(message: tr('No notifications'))])
          : ListView(padding: const EdgeInsets.all(12), children: [
              Align(
                alignment: Alignment.centerRight,
                child: TextButton.icon(
                  icon: const Icon(Icons.done_all),
                  label: Text(tr('Mark all read')),
                  onPressed: () async {
                    if (await runApi(context, () => api.post('/api/notifications/read-all'))) await reload();
                  },
                ),
              ),
              for (final n in list)
                Card(
                  color: n.read ? null : Theme.of(context).colorScheme.primaryContainer.withValues(alpha: 0.35),
                  child: ListTile(
                    title: Text(n.title, style: TextStyle(fontWeight: n.read ? null : FontWeight.bold)),
                    subtitle: Text('${n.body}\n${fmtDateTime(n.createdAt)}'),
                    isThreeLine: true,
                  ),
                ),
            ]),
    );
  }
}

class VendorAccountScreen extends StatelessWidget {
  const VendorAccountScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final session = context.watch<Session>();
    final me = session.me!;
    return ListView(padding: const EdgeInsets.all(16), children: [
      InfoCard(title: tr('Vendor account'), children: [
        InfoRow(tr('Name'), me.name),
        InfoRow(tr('Username'), me.username),
        InfoRow(tr('Server'), session.serverUrl),
        if (session.appVersion.isNotEmpty) InfoRow(tr('App version'), session.appVersion),
      ]),
      const SizedBox(height: 8),
      const Card(child: LanguageTile()),
      const SizedBox(height: 8),
      Wrap(spacing: 8, children: [
        OutlinedButton.icon(
          icon: const Icon(Icons.lock_outline),
          label: Text(tr('Change password')),
          onPressed: () => push(context, const ChangePasswordScreen()),
        ),
        OutlinedButton.icon(
          icon: const Icon(Icons.logout),
          label: Text(tr('Sign out')),
          onPressed: () => session.logout(),
        ),
      ]),
    ]);
  }
}
