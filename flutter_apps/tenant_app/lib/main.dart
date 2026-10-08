import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'package:tms_core/tms_core.dart';

import 'data/tenant_store.dart';
import 'l10n/bn_tenant.dart';
import 'screens/dues_screen.dart';
import 'screens/home_screen.dart';
import 'screens/maintenance_screen.dart';
import 'screens/profile_screen.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await AppLocale.instance.load();
  registerBangla(bnTenant);
  final session = Session(AppKind.tenant)..start();
  runApp(
    MultiProvider(
      providers: [
        ChangeNotifierProvider.value(value: AppLocale.instance),
        ChangeNotifierProvider.value(value: session),
        ChangeNotifierProvider(create: (_) => TenantStore(session)),
      ],
      child: const TenantApp(),
    ),
  );
}

class TenantApp extends StatelessWidget {
  const TenantApp({super.key});

  @override
  Widget build(BuildContext context) {
    final session = context.watch<Session>();
    final me = session.me;
    final locale = context.watch<AppLocale>().locale;
    Widget home;
    if (session.state == SessionState.starting) {
      home = const Scaffold(body: Center(child: CircularProgressIndicator()));
    } else if (session.update == UpdateStatus.required) {
      home = const UpdateRequiredScreen();
    } else if (!session.isSignedIn || me == null) {
      home = LoginScreen(
        title: tr('My Home'),
        icon: Icons.home_rounded,
        footer: Text(tr('Your landlord gives you the username and password for this app.'),
            textAlign: TextAlign.center),
      );
    } else if (me.mustChangePassword) {
      home = const ChangePasswordScreen(forced: true);
    } else if (session.blocked != null) {
      home = const LicenseLockScreen();
    } else {
      home = TenantShell(key: ValueKey(me.userId));
    }
    return MaterialApp(
      title: 'TMS Tenant',
      debugShowCheckedModeBanner: false,
      locale: locale,
      supportedLocales: tmsSupportedLocales,
      localizationsDelegates: tmsLocalizationsDelegates,
      theme: tmsTheme(Brightness.light, const Color(0xFF1565C0)),
      darkTheme: tmsTheme(Brightness.dark, const Color(0xFF1565C0)),
      home: home,
    );
  }
}

class TenantShell extends StatefulWidget {
  const TenantShell({super.key});

  @override
  State<TenantShell> createState() => _TenantShellState();
}

class _TenantShellState extends State<TenantShell> {
  int _tab = 0;

  List<String> get _titles => [tr('Home'), tr('Dues & payments'), tr('Maintenance'), tr('Alerts'), tr('Profile')];

  @override
  Widget build(BuildContext context) {
    final s = context.watch<TenantStore>();
    Widget body;
    if (!s.loaded) {
      body = Center(
        child: s.loading || s.loadError == null
            ? const CircularProgressIndicator()
            : Column(mainAxisSize: MainAxisSize.min, children: [
                Padding(padding: const EdgeInsets.all(16), child: Text(s.loadError!, textAlign: TextAlign.center)),
                FilledButton.icon(onPressed: s.refresh, icon: const Icon(Icons.refresh), label: Text(tr('Try again'))),
              ]),
      );
    } else {
      body = switch (_tab) {
        0 => HomeScreen(onNavigate: (t) => setState(() => _tab = t)),
        1 => const DuesScreen(),
        2 => const MaintenanceListScreen(),
        3 => const AlertsScreen(),
        _ => const ProfileScreen(),
      };
    }
    return Scaffold(
      appBar: AppBar(
        title: Text(_titles[_tab]),
        bottom: s.loading && s.loaded
            ? const PreferredSize(preferredSize: Size.fromHeight(2), child: LinearProgressIndicator(minHeight: 2))
            : null,
      ),
      body: Column(children: [
        const UpdateBanner(),
        const ReadOnlyBanner(),
        if (s.loaded && s.loadError != null)
          MaterialBanner(
            leading: const Icon(Icons.cloud_off),
            content: Text(s.loadError!),
            actions: [TextButton(onPressed: s.refresh, child: Text(tr('Retry')))],
          ),
        Expanded(child: body),
      ]),
      bottomNavigationBar: NavigationBar(
        selectedIndex: _tab,
        onDestinationSelected: (i) => setState(() => _tab = i),
        destinations: [
          NavigationDestination(
              icon: const Icon(Icons.home_outlined), selectedIcon: const Icon(Icons.home), label: tr('Home')),
          NavigationDestination(icon: const Icon(Icons.receipt_long_outlined), label: tr('Dues')),
          NavigationDestination(icon: const Icon(Icons.build_outlined), label: tr('Requests')),
          NavigationDestination(
            icon: Badge(
              isLabelVisible: s.unread > 0,
              label: Text('${s.unread}'),
              child: const Icon(Icons.notifications_outlined),
            ),
            label: tr('Alerts'),
          ),
          NavigationDestination(icon: const Icon(Icons.person_outline), label: tr('Profile')),
        ],
      ),
    );
  }
}
