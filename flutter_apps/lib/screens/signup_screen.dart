import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../widgets/common.dart';

/// Registers a new landlord business. The provider approves it before use.
class SignupScreen extends StatefulWidget {
  const SignupScreen({super.key});

  @override
  State<SignupScreen> createState() => _SignupScreenState();
}

class _SignupScreenState extends State<SignupScreen> {
  final _key = GlobalKey<FormState>();
  final _org = TextEditingController();
  final _name = TextEditingController();
  final _mobile = TextEditingController();
  final _email = TextEditingController();
  final _address = TextEditingController();
  final _username = TextEditingController();
  final _password = TextEditingController();
  final _confirm = TextEditingController();

  Future<void> _submit() async {
    if (!_key.currentState!.validate()) return;
    final session = context.read<Session>();
    var message = '';
    final ok = await runApi(
      context,
      () async => message = await session.signup(
        organizationName: _org.text,
        name: _name.text,
        username: _username.text,
        password: _password.text,
        mobile: _mobile.text,
        email: _email.text,
        address: _address.text,
      ),
    );
    if (!ok || !mounted) return;
    await showDialog(
      context: context,
      builder: (c) => AlertDialog(
        icon: const Icon(Icons.mark_email_read_outlined),
        title: Text(tr('Registration received')),
        content: Text(message.isEmpty
            ? tr('We will review your registration. Sign in with your new username to check its status.')
            : '$message\n\n${tr('Sign in with your new username to check its status.')}'),
        actions: [FilledButton(onPressed: () => Navigator.pop(c), child: Text(tr('OK')))],
      ),
    );
    if (mounted) Navigator.pop(context);
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: Text(tr('Register your business'))),
      body: Form(
        key: _key,
        child: ListView(padding: const EdgeInsets.all(16), children: [
          Center(
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 640),
              child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
                Text(tr('Create an account for your rental business. After the provider approves it, you can add properties, tenants and staff.')),
                SectionHeader(tr('Business')),
                FormGrid(children: [
                  AppTextField(controller: _org, label: tr('Business / organisation name *'), validator: requiredValidator),
                  AppTextField(controller: _name, label: tr('Your name *'), validator: requiredValidator),
                  AppTextField(
                      controller: _mobile,
                      label: tr('Mobile number *'),
                      keyboardType: TextInputType.phone,
                      validator: mobileValidator),
                  AppTextField(
                      controller: _email,
                      label: tr('Email'),
                      keyboardType: TextInputType.emailAddress,
                      validator: optionalEmailValidator),
                ]),
                const SizedBox(height: 12),
                AppTextField(controller: _address, label: tr('Address'), maxLines: 2),
                SectionHeader(tr('Your sign-in')),
                FormGrid(children: [
                  AppTextField(
                    controller: _username,
                    label: tr('Username *'),
                    latin: true,
                    validator: (v) {
                      final t = (v ?? '').trim();
                      if (t.length < 3) return tr('At least 3 characters');
                      if (!RegExp(r'^[A-Za-z0-9._-]+$').hasMatch(t)) return tr('Letters, digits, . _ - only');
                      return null;
                    },
                  ),
                  AppTextField(controller: _password, label: tr('Password *'), obscure: true, validator: validatePassword),
                  AppTextField(
                      controller: _confirm,
                      label: tr('Confirm password *'),
                      obscure: true,
                      validator: (v) => v != _password.text ? tr('Passwords do not match') : null),
                ]),
                const SizedBox(height: 20),
                FilledButton(
                  onPressed: _submit,
                  style: FilledButton.styleFrom(minimumSize: const Size.fromHeight(48)),
                  child: Text(tr('Register')),
                ),
              ]),
            ),
          ),
        ]),
      ),
    );
  }
}
