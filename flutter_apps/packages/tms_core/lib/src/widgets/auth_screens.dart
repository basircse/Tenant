import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'package:url_launcher/url_launcher.dart';

import '../api/api_client.dart';
import '../api/server_config.dart';
import '../api/session.dart';
import '../api/updates.dart';
import '../format.dart';
import '../l10n/i18n.dart';
import '../models/models.dart';
import 'common.dart';

/// Same rule as the server: at least 8 characters with letters and digits.
String? validatePassword(String? p) {
  final v = p ?? '';
  if (v.length < 8) return tr('Password must be at least 8 characters');
  if (!RegExp(r'[A-Za-z]').hasMatch(v) || !RegExp(r'\d').hasMatch(v)) {
    return tr('Password must contain letters and numbers');
  }
  return null;
}

/// Shared look for full-screen auth pages: gradient background and a card.
class AuthCard extends StatelessWidget {
  const AuthCard({super.key, required this.child, this.maxWidth = 420});

  final Widget child;
  final double maxWidth;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Container(
      decoration: BoxDecoration(
        gradient: LinearGradient(
          colors: [scheme.primaryContainer, scheme.surface],
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
        ),
      ),
      child: SafeArea(
        child: Center(
          child: SingleChildScrollView(
            padding: const EdgeInsets.all(16),
            child: ConstrainedBox(
              constraints: BoxConstraints(maxWidth: maxWidth),
              child: Card(child: Padding(padding: const EdgeInsets.all(28), child: child)),
            ),
          ),
        ),
      ),
    );
  }
}

class LoginScreen extends StatefulWidget {
  const LoginScreen({super.key, required this.title, required this.icon, this.footer});

  final String title;
  final IconData icon;

  /// Extra actions under the form (e.g. "Register your business").
  final Widget? footer;

  @override
  State<LoginScreen> createState() => _LoginScreenState();
}

class _LoginScreenState extends State<LoginScreen> {
  final _formKey = GlobalKey<FormState>();
  final _id = TextEditingController();
  final _pw = TextEditingController();
  bool _obscure = true;
  bool _busy = false;
  String? _error;

  Future<void> _submit() async {
    if (_busy || !_formKey.currentState!.validate()) return;
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      await context.read<Session>().login(_id.text, _pw.text);
    } on ApiException catch (e) {
      if (mounted) setState(() => _error = e.message);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _forgot() async {
    final id = await promptText(context, tr('Reset password'), label: tr('Username, email or mobile'), initial: _id.text);
    if (id == null || !mounted) return;
    final ok = await runApi(context, () => context.read<Session>().forgotPassword(id));
    if (!ok || !mounted) return;
    await showDialog(
      context: context,
      builder: (c) => AlertDialog(
        title: Text(tr('Request sent')),
        content: Text(tr('If the account exists, the administrator has been notified and will give you a temporary password. You will set a new password when you sign in.')),
        actions: [FilledButton(onPressed: () => Navigator.pop(c), child: Text(tr('OK')))],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final session = context.watch<Session>();
    final scheme = Theme.of(context).colorScheme;
    final notice = session.notice;
    return Scaffold(
      body: Stack(
        children: [
          AuthCard(
            child: Form(
              key: _formKey,
              child: AutofillGroup(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    const UpdateBanner(),
                    Icon(widget.icon, size: 56, color: scheme.primary),
                    const SizedBox(height: 8),
                    Text(widget.title,
                        textAlign: TextAlign.center, style: Theme.of(context).textTheme.headlineSmall),
                    const SizedBox(height: 24),
                    if (notice != null && _error == null) ...[
                      Text(notice, style: TextStyle(color: scheme.tertiary)),
                      const SizedBox(height: 12),
                    ],
                    TextFormField(
                      controller: _id,
                      autofillHints: const [AutofillHints.username],
                      inputFormatters: latinDigitInputs,
                      decoration: InputDecoration(
                        labelText: tr('Username / Email / Mobile'),
                        prefixIcon: const Icon(Icons.person_outline),
                      ),
                      validator: requiredValidator,
                      textInputAction: TextInputAction.next,
                    ),
                    const SizedBox(height: 12),
                    TextFormField(
                      controller: _pw,
                      obscureText: _obscure,
                      autofillHints: const [AutofillHints.password],
                      decoration: InputDecoration(
                        labelText: tr('Password'),
                        prefixIcon: const Icon(Icons.lock_outline),
                        suffixIcon: IconButton(
                          icon: Icon(_obscure ? Icons.visibility : Icons.visibility_off),
                          onPressed: () => setState(() => _obscure = !_obscure),
                        ),
                      ),
                      validator: requiredValidator,
                      onFieldSubmitted: (_) => _submit(),
                    ),
                    if (_error != null) ...[
                      const SizedBox(height: 12),
                      Text(_error!, style: TextStyle(color: scheme.error)),
                    ],
                    const SizedBox(height: 20),
                    FilledButton(
                      onPressed: _busy ? null : _submit,
                      style: FilledButton.styleFrom(minimumSize: const Size.fromHeight(48)),
                      child: _busy
                          ? const SizedBox(width: 22, height: 22, child: CircularProgressIndicator(strokeWidth: 2))
                          : Text(tr('Sign in')),
                    ),
                    TextButton(onPressed: _forgot, child: Text(tr('Forgot password?'))),
                    if (widget.footer != null) ...[const Divider(height: 24), widget.footer!],
                    if (session.appVersion.isNotEmpty)
                      Padding(
                        padding: const EdgeInsets.only(top: 8),
                        child: Text(tr('Version {version}', {'version': session.appVersion}),
                            textAlign: TextAlign.center, style: Theme.of(context).textTheme.bodySmall),
                      ),
                  ],
                ),
              ),
            ),
          ),
          const Positioned(top: 8, left: 8, child: SafeArea(child: LanguageButton())),
          if (ServerConfig.canChange)
            Positioned(
              top: 8,
              right: 8,
              child: SafeArea(
                child: IconButton(
                  tooltip: tr('Server: {url}', {'url': session.serverUrl}),
                  icon: const Icon(Icons.dns_outlined),
                  onPressed: () => showServerDialog(context),
                ),
              ),
            ),
        ],
      ),
    );
  }
}

/// Lets testers point the app at another server (debug builds only).
Future<void> showServerDialog(BuildContext context) async {
  final session = context.read<Session>();
  final ctrl = TextEditingController(text: session.serverUrl);
  final result = await showDialog<String>(
    context: context,
    builder: (c) => AlertDialog(
      title: Text(tr('Server address')),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          TextField(
            controller: ctrl,
            decoration: InputDecoration(labelText: tr('URL'), hintText: 'http://192.168.0.10:8080'),
            keyboardType: TextInputType.url,
          ),
          const SizedBox(height: 8),
          Text(tr('Default: {url}', {'url': ServerConfig.defaultUrl}), style: Theme.of(c).textTheme.bodySmall),
        ],
      ),
      actions: [
        TextButton(onPressed: () => Navigator.pop(c, ''), child: Text(tr('Use default'))),
        TextButton(onPressed: () => Navigator.pop(c), child: Text(tr('Cancel'))),
        FilledButton(onPressed: () => Navigator.pop(c, ctrl.text), child: Text(tr('Save'))),
      ],
    ),
  );
  if (result == null) return;
  await session.setServer(result);
}

class ChangePasswordScreen extends StatefulWidget {
  const ChangePasswordScreen({super.key, this.forced = false});

  /// True when the user must replace a temporary password before continuing.
  final bool forced;

  @override
  State<ChangePasswordScreen> createState() => _ChangePasswordScreenState();
}

class _ChangePasswordScreenState extends State<ChangePasswordScreen> {
  final _formKey = GlobalKey<FormState>();
  final _current = TextEditingController();
  final _next = TextEditingController();
  final _confirm = TextEditingController();

  Future<void> _save() async {
    if (!_formKey.currentState!.validate()) return;
    final session = context.read<Session>();
    final ok = await runApi(context, () => session.changePassword(_current.text, _next.text),
        success: tr('Password changed'));
    if (ok && !widget.forced && mounted) Navigator.pop(context);
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text(tr('Change password')),
        automaticallyImplyLeading: !widget.forced,
        actions: [
          if (widget.forced)
            TextButton(onPressed: () => context.read<Session>().logout(), child: Text(tr('Sign out'))),
        ],
      ),
      body: Center(
        child: SingleChildScrollView(
          padding: const EdgeInsets.all(16),
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 420),
            child: Form(
              key: _formKey,
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  if (widget.forced)
                    Padding(
                      padding: const EdgeInsets.only(bottom: 16),
                      child: Text(tr('You are using a temporary password. Please set a new password to continue.')),
                    ),
                  AppTextField(
                      controller: _current,
                      label: widget.forced ? tr('Temporary password') : tr('Current password'),
                      obscure: true,
                      validator: requiredValidator),
                  const SizedBox(height: 12),
                  AppTextField(controller: _next, label: tr('New password'), obscure: true, validator: validatePassword),
                  const SizedBox(height: 12),
                  AppTextField(
                      controller: _confirm,
                      label: tr('Confirm new password'),
                      obscure: true,
                      validator: (v) => v != _next.text ? tr('Passwords do not match') : null),
                  const SizedBox(height: 20),
                  FilledButton(onPressed: _save, child: Text(tr('Change password'))),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}

/// Shown while the server refuses service because of the licence.
class LicenseLockScreen extends StatefulWidget {
  const LicenseLockScreen({super.key, this.canRequestRenewal = false});

  /// Landlord admins can ask the vendor to renew from here.
  final bool canRequestRenewal;

  @override
  State<LicenseLockScreen> createState() => _LicenseLockScreenState();
}

class _LicenseLockScreenState extends State<LicenseLockScreen> {
  LicenseStatus? _status;
  bool _checking = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) => _check(silent: true));
  }

  Future<void> _check({bool silent = false}) async {
    setState(() => _checking = true);
    try {
      final s = await context.read<Session>().recheckLicense();
      if (mounted) setState(() => _status = s);
      if (!silent && mounted && s != null && !s.state.usable) {
        showSnack(context, tr('Still {state}.', {'state': s.state.label.toLowerCase()}));
      }
    } on ApiException catch (e) {
      if (!silent && mounted) showSnack(context, e.message, error: true);
    } finally {
      if (mounted) setState(() => _checking = false);
    }
  }

  Future<void> _requestRenewal() async {
    final msg = await promptText(context, tr('Request renewal'),
        label: tr('Message to the provider (optional)'), required: false);
    if (msg == null || !mounted) return;
    final session = context.read<Session>();
    await runApi(context, () => session.api.post('/api/license/renewal-request', {'message': msg}),
        success: tr('Request sent. You will be notified when it is renewed.'));
  }

  ({IconData icon, String title, String body}) _explain(String? code, Me? me) {
    final tenant = me?.isTenant ?? false;
    switch (code) {
      case LicenseCodes.pending:
        return (
          icon: Icons.hourglass_top,
          title: tr('Waiting for approval'),
          body: tr('Your registration has been received. You can use the app as soon as it is approved. Tap "Check again" after you hear from us.')
        );
      case LicenseCodes.rejected:
        return (
          icon: Icons.block,
          title: tr('Registration not approved'),
          body: tr('This registration was not approved. Please contact the provider.')
        );
      case LicenseCodes.suspended:
        return (
          icon: Icons.pause_circle_outline,
          title: tr('Account suspended'),
          body: tenant
              ? tr("Your landlord's account is suspended. Please contact your landlord.")
              : tr('This account is suspended. Please contact the provider.')
        );
      case LicenseCodes.deviceBlocked:
        return (
          icon: Icons.phonelink_lock,
          title: tr('Device not allowed'),
          body: tr('This device is no longer allowed to use the account. Ask your administrator or the provider.')
        );
      default:
        return (
          icon: Icons.event_busy,
          title: tr('Subscription expired'),
          body: tenant
              ? tr("Your landlord's subscription has expired. Please contact your landlord.")
              : tr('The subscription has expired. Request a renewal to continue.')
        );
    }
  }

  @override
  Widget build(BuildContext context) {
    final session = context.watch<Session>();
    final e = _explain(session.blocked?.code, session.me);
    final scheme = Theme.of(context).colorScheme;
    final s = _status;
    return Scaffold(
      body: AuthCard(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Icon(e.icon, size: 56, color: scheme.primary),
            const SizedBox(height: 12),
            Text(e.title, textAlign: TextAlign.center, style: Theme.of(context).textTheme.headlineSmall),
            const SizedBox(height: 12),
            Text(e.body, textAlign: TextAlign.center),
            if (session.me != null) ...[
              const SizedBox(height: 16),
              Text('${session.me!.orgName} • ${session.me!.name}',
                  textAlign: TextAlign.center, style: Theme.of(context).textTheme.bodySmall),
            ],
            if (s?.expiresOn != null) ...[
              const SizedBox(height: 4),
              Text(tr('Expired on {date}', {'date': fmtDate(s!.expiresOn)}),
                  textAlign: TextAlign.center, style: Theme.of(context).textTheme.bodySmall),
            ],
            const SizedBox(height: 24),
            FilledButton.icon(
              onPressed: _checking ? null : () => _check(),
              icon: const Icon(Icons.refresh),
              label: Text(tr('Check again')),
            ),
            if (widget.canRequestRenewal &&
                (session.blocked?.code == LicenseCodes.expired || session.blocked?.code == LicenseCodes.suspended)) ...[
              const SizedBox(height: 8),
              OutlinedButton.icon(
                onPressed: _requestRenewal,
                icon: const Icon(Icons.autorenew),
                label: Text(tr('Request renewal')),
              ),
            ],
            const SizedBox(height: 8),
            TextButton(onPressed: () => session.logout(), child: Text(tr('Sign out'))),
            const SizedBox(height: 8),
            const LanguageTile(),
          ],
        ),
      ),
    );
  }
}

/// Thin banner shown during the read-only grace period.
class ReadOnlyBanner extends StatelessWidget {
  const ReadOnlyBanner({super.key});

  @override
  Widget build(BuildContext context) {
    final session = context.watch<Session>();
    if (!session.readOnly) return const SizedBox.shrink();
    final scheme = Theme.of(context).colorScheme;
    return Material(
      color: scheme.errorContainer,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
        child: Row(children: [
          Icon(Icons.lock_clock, color: scheme.onErrorContainer, size: 18),
          const SizedBox(width: 8),
          Expanded(
            child: Text(
              session.me?.isTenant ?? false
                  ? tr("Your landlord's subscription has expired. Some features are unavailable.")
                  : tr('Subscription expired: the app is read-only. Renew to make changes.'),
              style: TextStyle(color: scheme.onErrorContainer),
            ),
          ),
        ]),
      ),
    );
  }
}

ThemeData tmsTheme(Brightness b, Color seed) {
  final scheme = ColorScheme.fromSeed(seedColor: seed, brightness: b);
  return ThemeData(
    colorScheme: scheme,
    useMaterial3: true,
    cardTheme: CardThemeData(
      elevation: 0,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(12),
        side: BorderSide(color: scheme.outlineVariant),
      ),
    ),
    inputDecorationTheme: const InputDecorationTheme(border: OutlineInputBorder()),
  );
}

Future<void> openDownload(BuildContext context, String? url) async {
  if (url == null) {
    showSnack(context, tr('Ask your provider for the new version.'));
    return;
  }
  final ok = await launchUrl(Uri.parse(url), mode: LaunchMode.externalApplication);
  if (!ok && context.mounted) showSnack(context, tr('Open {url} in a browser to download the update.', {'url': url}));
}

/// Offers a newer version (dismissible).
class UpdateBanner extends StatelessWidget {
  const UpdateBanner({super.key});

  @override
  Widget build(BuildContext context) {
    final session = context.watch<Session>();
    if (session.update != UpdateStatus.available || session.updateDismissed) return const SizedBox.shrink();
    final r = session.release!;
    return MaterialBanner(
      leading: const Icon(Icons.system_update),
      content: Text(r.notes == null
          ? tr('Version {version} is available', {'version': r.latestVersion})
          : tr('Version {version} is available: {notes}', {'version': r.latestVersion, 'notes': r.notes})),
      actions: [
        TextButton(
          onPressed: session.dismissUpdate,
          child: Text(tr('Later')),
        ),
        FilledButton(onPressed: () => openDownload(context, r.downloadUrl), child: Text(tr('Update'))),
      ],
    );
  }
}

/// Shown when the installed version is older than the minimum the server accepts.
class UpdateRequiredScreen extends StatelessWidget {
  const UpdateRequiredScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final session = context.watch<Session>();
    final r = session.release;
    return Scaffold(
      body: AuthCard(
        child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          Icon(Icons.system_update, size: 56, color: Theme.of(context).colorScheme.primary),
          const SizedBox(height: 12),
          Text(tr('Update required'), textAlign: TextAlign.center, style: Theme.of(context).textTheme.headlineSmall),
          const SizedBox(height: 12),
          Text(
              tr('This version ({current}) is no longer supported. Please install version {version} to continue.',
                  {'current': session.appVersion, 'version': r?.latestVersion ?? r?.minVersion}),
              textAlign: TextAlign.center),
          if (r?.notes != null) ...[const SizedBox(height: 8), Text(r!.notes!, textAlign: TextAlign.center)],
          const SizedBox(height: 24),
          FilledButton.icon(
            onPressed: () => openDownload(context, r?.downloadUrl),
            icon: const Icon(Icons.download),
            label: Text(tr('Download update')),
          ),
          TextButton(onPressed: session.checkForUpdate, child: Text(tr('Check again'))),
          const SizedBox(height: 8),
          const LanguageTile(),
        ]),
      ),
    );
  }
}
