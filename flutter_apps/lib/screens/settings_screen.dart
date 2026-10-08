import 'package:flutter/material.dart';

import '../widgets/common.dart';

class SettingsScreen extends StatefulWidget {
  const SettingsScreen({super.key});

  @override
  State<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends State<SettingsScreen> {
  final _key = GlobalKey<FormState>();
  late final AppSettings _s = context.store.settings;
  late final _org = TextEditingController(text: _s.orgName);
  late final _contact = TextEditingController(text: _s.contactName);
  late final _phone = TextEditingController(text: _s.phone);
  late final _email = TextEditingController(text: _s.email);
  late final _address = TextEditingController(text: _s.address);
  late final _currency = TextEditingController(text: _s.currency);
  late final _alertDays = TextEditingController(text: '${_s.expiryAlertDays}');
  late bool _autoRent = _s.autoGenerateRent;

  void _save() {
    if (!_key.currentState!.validate()) return;
    runGuarded(
      context,
      () => context.store.updateSettings(AppSettings(
        orgName: _org.text.trim(),
        contactName: _contact.text.trim(),
        phone: _phone.text.trim(),
        email: _email.text.trim(),
        address: _address.text.trim(),
        currency: _currency.text.trim(),
        expiryAlertDays: int.parse(latinDigits(_alertDays.text.trim())),
        autoGenerateRent: _autoRent,
      )),
      success: tr('Settings saved'),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Form(
      key: _key,
      child: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          Center(
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 700),
              child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
                SectionHeader(tr('Organisation')),
                FormGrid(children: [
                  AppTextField(controller: _org, label: tr('Organisation name'), validator: requiredValidator),
                  AppTextField(controller: _contact, label: tr('Contact person')),
                  AppTextField(controller: _phone, label: tr('Phone'), keyboardType: TextInputType.phone),
                  AppTextField(
                      controller: _email,
                      label: tr('Email'),
                      keyboardType: TextInputType.emailAddress,
                      validator: optionalEmailValidator),
                  AppTextField(controller: _address, label: tr('Address')),
                  AppTextField(controller: _currency, label: tr('Currency symbol'), validator: requiredValidator),
                ]),
                SectionHeader(tr('Rent & agreements')),
                AppTextField(
                  controller: _alertDays,
                  label: tr('Agreement expiry alert (days before end)'),
                  keyboardType: TextInputType.number,
                  validator: (v) {
                    final n = int.tryParse(latinDigits((v ?? '').trim()));
                    return n == null || n < 1 || n > 365 ? tr('Enter 1-365') : null;
                  },
                ),
                SwitchListTile(
                  contentPadding: EdgeInsets.zero,
                  title: Text(tr('Generate monthly rent automatically')),
                  subtitle: Text(
                      tr('The server creates invoices for the current month for all active agreements every day.')),
                  value: _autoRent,
                  onChanged: (v) => setState(() => _autoRent = v),
                ),
                const SizedBox(height: 12),
                Align(
                  alignment: Alignment.centerRight,
                  child: FilledButton.icon(
                      onPressed: _save, icon: const Icon(Icons.save), label: Text(tr('Save settings'))),
                ),
              ]),
            ),
          ),
        ],
      ),
    );
  }
}
