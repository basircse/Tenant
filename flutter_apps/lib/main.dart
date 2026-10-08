import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'package:tms_core/tms_core.dart';

import 'data/app_store.dart';
import 'l10n/bn_admin_a.dart';
import 'l10n/bn_admin_b.dart';
import 'l10n/bn_vendor.dart';
import 'screens/home_shell.dart';
import 'screens/signup_screen.dart';
import 'vendor/vendor_screens.dart';

const _bnMain = {
  'TMS Admin': 'TMS অ্যাডমিন',
  'Register your business': 'আপনার ব্যবসা নিবন্ধন করুন',
};

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await AppLocale.instance.load();
  registerBangla({...bnAdminA, ...bnAdminB, ...bnVendor, ..._bnMain});
  final session = Session(AppKind.admin)..start();
  runApp(
    MultiProvider(
      providers: [
        ChangeNotifierProvider.value(value: AppLocale.instance),
        ChangeNotifierProvider.value(value: session),
        ChangeNotifierProvider(create: (_) => AppStore(session)),
      ],
      child: const TenantManagementApp(),
    ),
  );
}

class TenantManagementApp extends StatelessWidget {
  const TenantManagementApp({super.key});

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
        title: tr('TMS Admin'),
        icon: Icons.apartment,
        footer: Builder(
          builder: (context) => OutlinedButton.icon(
            icon: const Icon(Icons.add_business_outlined),
            label: Text(tr('Register your business')),
            onPressed: () => push(context, const SignupScreen()),
          ),
        ),
      );
    } else if (me.mustChangePassword) {
      home = const ChangePasswordScreen(forced: true);
    } else if (session.blocked != null) {
      home = LicenseLockScreen(canRequestRenewal: me.role == UserRole.admin);
    } else if (me.isVendor) {
      home = const VendorShell();
    } else {
      home = HomeShell(key: ValueKey(me.userId));
    }
    return MaterialApp(
      title: 'TMS Admin',
      debugShowCheckedModeBanner: false,
      locale: locale,
      supportedLocales: tmsSupportedLocales,
      localizationsDelegates: tmsLocalizationsDelegates,
      theme: tmsTheme(Brightness.light, const Color(0xFF00695C)),
      darkTheme: tmsTheme(Brightness.dark, const Color(0xFF00695C)),
      home: home,
    );
  }
}
