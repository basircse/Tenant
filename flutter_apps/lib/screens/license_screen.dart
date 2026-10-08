import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../widgets/common.dart';

/// The landlord's subscription: state, limits, renewal and admin devices.
class LicenseScreen extends StatefulWidget {
  const LicenseScreen({super.key});

  @override
  State<LicenseScreen> createState() => _LicenseScreenState();
}

class _LicenseScreenState extends State<LicenseScreen> {
  late Future<List<DeviceInfoView>> _devices = context.store.devices();

  void _reloadDevices() => setState(() => _devices = context.store.devices());

  Future<void> _requestRenewal() async {
    final msg = await promptText(context, tr('Request renewal'),
        label: tr('Message to the provider (optional)'), required: false);
    if (msg == null || !mounted) return;
    await runGuarded(context, () => context.store.requestRenewal(msg),
        success: tr('Request sent. You will be notified when it is renewed.'));
  }

  @override
  Widget build(BuildContext context) {
    final s = context.watchStore;
    final session = context.watch<Session>();
    final l = s.license;
    if (l == null) return const Center(child: CircularProgressIndicator());
    final thisDevice = session.deviceId;
    return ListView(padding: const EdgeInsets.all(16), children: [
      Row(children: [
        Expanded(child: Text(l.plan ?? tr('Subscription'), style: Theme.of(context).textTheme.headlineSmall)),
        StatusChip(l.state),
      ]),
      if (l.message.isNotEmpty) Padding(padding: const EdgeInsets.only(top: 8), child: Text(l.message)),
      const SizedBox(height: 12),
      ResponsiveGrid(children: [
        StatCard(
          label: tr('Valid until'),
          value: l.expiresOn == null ? tr('No expiry') : fmtDate(l.expiresOn),
          subtitle: l.daysLeft == null
              ? null
              : (l.daysLeft! >= 0 ? tr('{n} days left', {'n': l.daysLeft}) : tr('Expired')),
          icon: Icons.event,
        ),
        StatCard(
          label: tr('Units'),
          value: '${l.unitsUsed} / ${l.maxUnits ?? '∞'}',
          subtitle: tr('Inactive units do not count'),
          icon: Icons.door_front_door_outlined,
        ),
        StatCard(
          label: tr('Admin devices'),
          value: '${l.devicesUsed} / ${l.maxDevices ?? '∞'}',
          icon: Icons.devices,
        ),
      ]),
      if (l.graceEndsOn != null && l.readOnly)
        Padding(
          padding: const EdgeInsets.only(top: 8),
          child: Text(tr('Read-only until {date}, then the app locks for everyone.', {'date': fmtDate(l.graceEndsOn)}),
              style: TextStyle(color: Theme.of(context).colorScheme.error)),
        ),
      const SizedBox(height: 12),
      Align(
        alignment: Alignment.centerLeft,
        child: FilledButton.icon(
            onPressed: _requestRenewal, icon: const Icon(Icons.autorenew), label: Text(tr('Request renewal'))),
      ),
      SectionHeader(tr('Devices using the admin app')),
      Text(tr('Each phone or computer that signs in to this app takes one device slot. Remove devices that are no longer used to free a slot. Tenant app devices are not limited.')),
      const SizedBox(height: 8),
      FutureBuilder<List<DeviceInfoView>>(
        future: _devices,
        builder: (context, snap) {
          if (snap.hasError) return Text('${snap.error}');
          if (!snap.hasData) return const Padding(padding: EdgeInsets.all(16), child: LinearProgressIndicator());
          final list = snap.data!.where((d) => d.app == 'ADMIN').toList();
          return Column(children: [
            for (final d in list)
              Card(
                child: ListTile(
                  leading: Icon(d.platform == 'android' ? Icons.phone_android : Icons.computer),
                  title: Text(d.id == thisDevice ? tr('{name} (this device)', {'name': d.name}) : d.name),
                  subtitle: Text(tr('Last used {used} • added {added}',
                      {'used': fmtDateTime(d.lastSeenAt), 'added': fmtDate(d.registeredAt)})),
                  trailing: Row(mainAxisSize: MainAxisSize.min, children: [
                    StatusChip(d.status),
                    if (d.id != thisDevice)
                      IconButton(
                        tooltip: tr('Remove device'),
                        icon: const Icon(Icons.delete_outline),
                        onPressed: () async {
                          if (await confirmDialog(context, tr('Remove device'),
                                  tr('{name} will be signed out and must be approved again to sign in.', {'name': d.name}),
                                  confirm: tr('Remove'), destructive: true) &&
                              context.mounted &&
                              await runGuarded(context, () => s.removeDevice(d), success: tr('Device removed'))) {
                            _reloadDevices();
                          }
                        },
                      ),
                  ]),
                ),
              ),
          ]);
        },
      ),
    ]);
  }
}
