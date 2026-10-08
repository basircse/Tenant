import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../api/api_client.dart';
import '../format.dart';
import '../l10n/i18n.dart';
import '../models/models.dart';

/// Runs an API [action] behind a progress indicator and shows any
/// [ApiException] as a snackbar. Returns true on success.
Future<bool> runApi(BuildContext context, FutureOr<void> Function() action,
    {String? success, bool showProgress = true}) async {
  final navigator = Navigator.of(context, rootNavigator: true);
  final messenger = ScaffoldMessenger.maybeOf(context);
  final errorColor = Theme.of(context).colorScheme.error;
  var dialogOpen = false;
  if (showProgress) {
    dialogOpen = true;
    unawaited(showDialog<void>(
      context: context,
      barrierDismissible: false,
      useRootNavigator: true,
      builder: (_) => const PopScope(
        canPop: false,
        child: Center(child: CircularProgressIndicator()),
      ),
    ).whenComplete(() => dialogOpen = false));
  }
  void close() {
    if (dialogOpen) {
      dialogOpen = false;
      navigator.pop();
    }
  }

  void snack(String text, {bool error = false}) {
    messenger?.hideCurrentSnackBar();
    messenger?.showSnackBar(SnackBar(
      content: Text(text),
      backgroundColor: error ? errorColor : null,
      behavior: SnackBarBehavior.floating,
    ));
  }

  try {
    await action();
    close();
    if (success != null) snack(success);
    return true;
  } on ApiException catch (e) {
    close();
    snack(e.message, error: true);
    return false;
  } catch (e) {
    close();
    snack(tr('Something went wrong: {error}', {'error': e}), error: true);
    return false;
  }
}

void showSnack(BuildContext context, String message, {bool error = false}) {
  final messenger = ScaffoldMessenger.maybeOf(context);
  messenger?.hideCurrentSnackBar();
  messenger?.showSnackBar(SnackBar(
    content: Text(message),
    backgroundColor: error ? Theme.of(context).colorScheme.error : null,
    behavior: SnackBarBehavior.floating,
  ));
}

Future<bool> confirmDialog(BuildContext context, String title, String message,
    {String? confirm, bool destructive = false}) async {
  final res = await showDialog<bool>(
    context: context,
    builder: (c) => AlertDialog(
      title: Text(title),
      content: Text(message),
      actions: [
        TextButton(onPressed: () => Navigator.pop(c, false), child: Text(tr('Cancel'))),
        FilledButton(
          style: destructive
              ? FilledButton.styleFrom(backgroundColor: Theme.of(c).colorScheme.error)
              : null,
          onPressed: () => Navigator.pop(c, true),
          child: Text(confirm ?? tr('Confirm')),
        ),
      ],
    ),
  );
  return res ?? false;
}

/// Prompts for a single line of text (e.g. a reason). Returns null if cancelled.
Future<String?> promptText(BuildContext context, String title,
    {String? label, String initial = '', bool required = true}) async {
  final ctrl = TextEditingController(text: initial);
  final key = GlobalKey<FormState>();
  final res = await showDialog<String>(
    context: context,
    builder: (c) => AlertDialog(
      title: Text(title),
      content: Form(
        key: key,
        child: TextFormField(
          controller: ctrl,
          autofocus: true,
          decoration: InputDecoration(labelText: label ?? tr('Reason')),
          validator: (v) => required && (v == null || v.trim().isEmpty) ? tr('Required') : null,
        ),
      ),
      actions: [
        TextButton(onPressed: () => Navigator.pop(c), child: Text(tr('Cancel'))),
        FilledButton(
          onPressed: () {
            if (key.currentState!.validate()) Navigator.pop(c, ctrl.text.trim());
          },
          child: Text(tr('OK')),
        ),
      ],
    ),
  );
  return res;
}

/// Shows exported CSV text with a copy button (paste into Excel / Sheets).
Future<void> showExportDialog(BuildContext context, String title, String csv) {
  return showDialog(
    context: context,
    builder: (c) => AlertDialog(
      title: Text(tr('Export: {title}', {'title': title})),
      content: SizedBox(
        width: 600,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(tr('CSV data (opens in Excel / Google Sheets). Copy and save as .csv:')),
            const SizedBox(height: 8),
            Flexible(
              child: Container(
                padding: const EdgeInsets.all(8),
                decoration: BoxDecoration(
                  border: Border.all(color: Theme.of(c).dividerColor),
                  borderRadius: BorderRadius.circular(8),
                ),
                child: SingleChildScrollView(
                  child: SelectableText(csv,
                      style: const TextStyle(fontFamily: 'monospace', fontSize: 12)),
                ),
              ),
            ),
          ],
        ),
      ),
      actions: [
        TextButton(onPressed: () => Navigator.pop(c), child: Text(tr('Close'))),
        FilledButton.icon(
          icon: const Icon(Icons.copy),
          label: Text(tr('Copy CSV')),
          onPressed: () {
            Clipboard.setData(ClipboardData(text: csv));
            Navigator.pop(c);
            showSnack(context, tr('CSV copied to clipboard'));
          },
        ),
      ],
    ),
  );
}

class StatCard extends StatelessWidget {
  const StatCard({
    super.key,
    required this.label,
    required this.value,
    required this.icon,
    this.color,
    this.onTap,
    this.subtitle,
  });

  final String label;
  final String value;
  final IconData icon;
  final Color? color;
  final VoidCallback? onTap;
  final String? subtitle;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final c = color ?? scheme.primary;
    return Card(
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Row(
            children: [
              Container(
                padding: const EdgeInsets.all(10),
                decoration: BoxDecoration(
                  color: c.withValues(alpha: 0.12),
                  borderRadius: BorderRadius.circular(12),
                ),
                child: Icon(icon, color: c),
              ),
              const SizedBox(width: 14),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(label,
                        style: Theme.of(context).textTheme.bodySmall,
                        overflow: TextOverflow.ellipsis),
                    const SizedBox(height: 2),
                    FittedBox(
                      fit: BoxFit.scaleDown,
                      alignment: Alignment.centerLeft,
                      child: Text(value,
                          style: Theme.of(context)
                              .textTheme
                              .titleLarge
                              ?.copyWith(fontWeight: FontWeight.w700)),
                    ),
                    if (subtitle != null)
                      Text(subtitle!,
                          style: Theme.of(context).textTheme.bodySmall,
                          overflow: TextOverflow.ellipsis),
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

/// Lays out children in a responsive grid with a minimum tile width.
class ResponsiveGrid extends StatelessWidget {
  const ResponsiveGrid({super.key, required this.children, this.minTileWidth = 165});

  final List<Widget> children;
  final double minTileWidth;

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(builder: (context, c) {
      final cols = (c.maxWidth / minTileWidth).floor().clamp(1, 6);
      final w = (c.maxWidth - (cols - 1) * 8) / cols;
      return Wrap(
        spacing: 8,
        runSpacing: 8,
        children: [for (final ch in children) SizedBox(width: w, child: ch)],
      );
    });
  }
}

/// "Blocked by Karim on 7 Oct 2026: Left the company" for a blocked user.
String blockedText(AppUser u) => tr('Blocked by {by} on {date}: {reason}', {
      'by': u.blockedByVendor ? tr('the service provider') : u.blockedBy,
      'date': fmtDateTime(u.blockedAt),
      'reason': u.blockedReason,
    });

Color statusColor(BuildContext context, Enum status) {
  const green = Color(0xFF2E7D32);
  const orange = Color(0xFFEF6C00);
  const red = Color(0xFFC62828);
  const blue = Color(0xFF1565C0);
  const grey = Color(0xFF757575);
  const purple = Color(0xFF6A1B9A);
  switch (status) {
    case UnitStatus.available:
    case RentStatus.paid:
    case AgreementStatus.active:
    case TenantStatus.active:
    case UserStatus.active:
    case RecordStatus.active:
    case MaintenanceStatus.completed:
      return green;
    case UnitStatus.occupied:
    case MaintenanceStatus.assigned:
    case RentStatus.partiallyPaid:
      return blue;
    case UnitStatus.reserved:
    case AgreementStatus.draft:
    case MaintenanceStatus.inProgress:
      return purple;
    case UnitStatus.maintenance:
    case AgreementStatus.expiring:
    case RentStatus.pending:
    case MaintenanceStatus.open:
    case Priority.high:
      return orange;
    case RentStatus.overdue:
    case UserStatus.locked:
    case UserStatus.blocked:
    case Priority.urgent:
    case AgreementStatus.terminated:
      return red;
    case Priority.medium:
      return blue;
    case Priority.low:
    case LicenseState.active:
    case DeviceStatus.approved:
      return green;
    case LicenseState.pending:
    case LicenseState.grace:
    case DeviceStatus.pending:
      return orange;
    case LicenseState.expired:
    case LicenseState.suspended:
    case LicenseState.rejected:
    case DeviceStatus.blocked:
      return red;
    default:
      return grey;
  }
}

String enumLabel(Enum e) {
  try {
    return (e as dynamic).label as String;
  } catch (_) {
    return e.name;
  }
}

class StatusChip extends StatelessWidget {
  const StatusChip(this.status, {super.key});

  final Enum status;

  @override
  Widget build(BuildContext context) {
    final c = statusColor(context, status);
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
      decoration: BoxDecoration(
        color: c.withValues(alpha: 0.12),
        borderRadius: BorderRadius.circular(20),
        border: Border.all(color: c.withValues(alpha: 0.4)),
      ),
      child: Text(enumLabel(status),
          style: TextStyle(color: c, fontSize: 12, fontWeight: FontWeight.w600)),
    );
  }
}

class EmptyState extends StatelessWidget {
  const EmptyState({super.key, required this.message, this.icon = Icons.inbox_outlined, this.action});

  final String message;
  final IconData icon;
  final Widget? action;

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(32),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(icon, size: 56, color: Theme.of(context).disabledColor),
            const SizedBox(height: 12),
            Text(message, textAlign: TextAlign.center),
            if (action != null) ...[const SizedBox(height: 12), action!],
          ],
        ),
      ),
    );
  }
}

class SectionHeader extends StatelessWidget {
  const SectionHeader(this.title, {super.key, this.trailing});

  final String title;
  final Widget? trailing;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(4, 16, 4, 8),
      child: Row(
        children: [
          Expanded(
            child: Text(title,
                style:
                    Theme.of(context).textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w600)),
          ),
          if (trailing != null) trailing!,
        ],
      ),
    );
  }
}

/// Label/value row used on detail screens.
class InfoRow extends StatelessWidget {
  const InfoRow(this.label, this.value, {super.key});

  final String label;
  final String value;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          SizedBox(
            width: 150,
            child: Text(label, style: TextStyle(color: Theme.of(context).hintColor)),
          ),
          Expanded(child: SelectableText(value.isEmpty ? '-' : value)),
        ],
      ),
    );
  }
}

class InfoCard extends StatelessWidget {
  const InfoCard({super.key, required this.title, required this.children, this.trailing});

  final String title;
  final List<Widget> children;
  final Widget? trailing;

  @override
  Widget build(BuildContext context) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Expanded(
                  child: Text(title,
                      style: Theme.of(context)
                          .textTheme
                          .titleMedium
                          ?.copyWith(fontWeight: FontWeight.w600)),
                ),
                if (trailing != null) trailing!,
              ],
            ),
            const Divider(),
            ...children,
          ],
        ),
      ),
    );
  }
}

/// Search box + optional filter widgets in a wrapping toolbar.
class FilterBar extends StatelessWidget {
  const FilterBar({super.key, this.onSearch, this.hint, this.filters = const []});

  final ValueChanged<String>? onSearch;
  final String? hint;
  final List<Widget> filters;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(12, 12, 12, 4),
      child: Wrap(
        spacing: 8,
        runSpacing: 8,
        crossAxisAlignment: WrapCrossAlignment.center,
        children: [
          if (onSearch != null)
            SizedBox(
              width: 280,
              child: TextField(
                decoration: InputDecoration(
                  prefixIcon: const Icon(Icons.search),
                  hintText: hint ?? tr('Search'),
                  isDense: true,
                  border: const OutlineInputBorder(),
                ),
                onChanged: onSearch,
              ),
            ),
          ...filters,
        ],
      ),
    );
  }
}

/// Compact dropdown used in filter bars. A null value means "All".
class FilterDropdown<T> extends StatelessWidget {
  const FilterDropdown({
    super.key,
    required this.label,
    required this.value,
    required this.items,
    required this.onChanged,
    this.allowAll = true,
  });

  final String label;
  final T? value;
  final Map<T, String> items;
  final ValueChanged<T?> onChanged;
  final bool allowAll;

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      width: 190,
      child: DropdownButtonFormField<T?>(
        initialValue: value,
        isExpanded: true,
        decoration: InputDecoration(
          labelText: label,
          isDense: true,
          border: const OutlineInputBorder(),
        ),
        items: [
          if (allowAll) DropdownMenuItem<T?>(value: null, child: Text(tr('All'))),
          for (final e in items.entries)
            DropdownMenuItem<T?>(value: e.key, child: Text(e.value, overflow: TextOverflow.ellipsis)),
        ],
        onChanged: onChanged,
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// Form helpers
// ---------------------------------------------------------------------------

String? requiredValidator(String? v) => (v == null || v.trim().isEmpty) ? tr('Required') : null;

String? mobileValidator(String? v) {
  if (v == null || v.trim().isEmpty) return tr('Required');
  if (!RegExp(r'^\+?\d{10,14}$').hasMatch(latinDigits(v.trim()))) return tr('Enter a valid mobile number');
  return null;
}

String? optionalEmailValidator(String? v) {
  if (v == null || v.trim().isEmpty) return null;
  if (!RegExp(r'^[^@\s]+@[^@\s]+\.[^@\s]+$').hasMatch(v.trim())) return tr('Enter a valid email');
  return null;
}

String? amountValidator(String? v, {bool allowZero = true}) {
  if (v == null || v.trim().isEmpty) return allowZero ? null : tr('Required');
  final n = double.tryParse(latinDigits(v.trim()));
  if (n == null || n < 0) return tr('Enter a valid amount');
  if (!allowZero && n == 0) return tr('Must be greater than zero');
  return null;
}

double parseAmount(String s) => double.tryParse(latinDigits(s.trim())) ?? 0;

class AppTextField extends StatelessWidget {
  const AppTextField({
    super.key,
    required this.controller,
    required this.label,
    this.validator,
    this.keyboardType,
    this.maxLines = 1,
    this.obscure = false,
    this.enabled = true,
    this.hint,
    this.prefixText,
    this.latin = false,
  });

  final TextEditingController controller;
  final String label;
  final String? Function(String?)? validator;
  final TextInputType? keyboardType;

  /// Convert Bangla digits to Latin while typing (number and phone fields always do).
  final bool latin;

  static bool _numeric(TextInputType? t) =>
      t != null && (t.index == TextInputType.number.index || t.index == TextInputType.phone.index);
  final int maxLines;
  final bool obscure;
  final bool enabled;
  final String? hint;
  final String? prefixText;

  @override
  Widget build(BuildContext context) {
    return TextFormField(
      controller: controller,
      validator: validator,
      keyboardType: keyboardType,
      inputFormatters: _numeric(keyboardType) || latin ? latinDigitInputs : null,
      maxLines: maxLines,
      obscureText: obscure,
      enabled: enabled,
      decoration: InputDecoration(
        labelText: label,
        hintText: hint,
        prefixText: prefixText,
        border: const OutlineInputBorder(),
      ),
    );
  }
}

class AmountField extends StatelessWidget {
  const AmountField({super.key, required this.controller, required this.label, this.required = false});

  final TextEditingController controller;
  final String label;
  final bool required;

  @override
  Widget build(BuildContext context) {
    return AppTextField(
      controller: controller,
      label: label,
      keyboardType: const TextInputType.numberWithOptions(decimal: true),
      validator: (v) => amountValidator(v, allowZero: !required),
      prefixText: '$currencySymbol ',
    );
  }
}

class AppDropdown<T> extends StatelessWidget {
  const AppDropdown({
    super.key,
    required this.label,
    required this.value,
    required this.items,
    required this.onChanged,
    this.validator,
  });

  final String label;
  final T? value;
  final Map<T, String> items;
  final ValueChanged<T?>? onChanged;
  final String? Function(T?)? validator;

  @override
  Widget build(BuildContext context) {
    return DropdownButtonFormField<T>(
      initialValue: items.containsKey(value) ? value : null,
      isExpanded: true,
      decoration: InputDecoration(labelText: label, border: const OutlineInputBorder()),
      items: [
        for (final e in items.entries)
          DropdownMenuItem<T>(value: e.key, child: Text(e.value, overflow: TextOverflow.ellipsis)),
      ],
      onChanged: onChanged,
      validator: validator ?? (v) => v == null ? tr('Required') : null,
    );
  }
}

class DateField extends StatelessWidget {
  const DateField({
    super.key,
    required this.label,
    required this.value,
    required this.onChanged,
    this.firstDate,
    this.lastDate,
    this.required = true,
  });

  final String label;
  final DateTime? value;
  final ValueChanged<DateTime?> onChanged;
  final DateTime? firstDate;
  final DateTime? lastDate;
  final bool required;

  @override
  Widget build(BuildContext context) {
    return FormField<DateTime>(
      initialValue: value,
      validator: (_) => required && value == null ? tr('Required') : null,
      builder: (state) => InkWell(
        onTap: () async {
          final picked = await showDatePicker(
            context: context,
            initialDate: value ?? today(),
            firstDate: firstDate ?? DateTime(1940),
            lastDate: lastDate ?? DateTime(2100),
          );
          if (picked != null) {
            onChanged(picked);
            state.didChange(picked);
          }
        },
        child: InputDecorator(
          decoration: InputDecoration(
            labelText: label,
            border: const OutlineInputBorder(),
            suffixIcon: const Icon(Icons.calendar_today, size: 18),
            errorText: state.errorText,
          ),
          child: Text(value == null ? '' : fmtDate(value)),
        ),
      ),
    );
  }
}

/// Two-column form layout on wide screens, one column on narrow.
class FormGrid extends StatelessWidget {
  const FormGrid({super.key, required this.children});

  final List<Widget> children;

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(builder: (context, c) {
      final twoCol = c.maxWidth > 560;
      final w = twoCol ? (c.maxWidth - 12) / 2 : c.maxWidth;
      return Wrap(
        spacing: 12,
        runSpacing: 12,
        children: [for (final ch in children) SizedBox(width: w, child: ch)],
      );
    });
  }
}

/// Standard scaffold for add/edit forms with a save button.
class FormPage extends StatelessWidget {
  const FormPage({
    super.key,
    required this.title,
    required this.formKey,
    required this.onSave,
    required this.children,
    this.saveLabel,
  });

  final String title;
  final GlobalKey<FormState> formKey;
  final VoidCallback onSave;
  final List<Widget> children;
  final String? saveLabel;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: Text(title)),
      body: Form(
        key: formKey,
        child: Center(
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 900),
            child: ListView(
              padding: const EdgeInsets.all(16),
              children: [
                ...children,
                const SizedBox(height: 24),
                Align(
                  alignment: Alignment.centerRight,
                  child: FilledButton.icon(
                    icon: const Icon(Icons.save),
                    label: Text(saveLabel ?? tr('Save')),
                    onPressed: () {
                      if (formKey.currentState!.validate()) onSave();
                    },
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

/// A horizontally scrollable DataTable that fills the available width.
class ScrollableTable extends StatelessWidget {
  const ScrollableTable({super.key, required this.columns, required this.rows});

  final List<DataColumn> columns;
  final List<DataRow> rows;

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(builder: (context, c) {
      return SingleChildScrollView(
        scrollDirection: Axis.horizontal,
        child: ConstrainedBox(
          constraints: BoxConstraints(minWidth: c.maxWidth),
          child: DataTable(
            columns: columns,
            rows: rows,
            headingRowHeight: 44,
            dataRowMinHeight: 40,
            dataRowMaxHeight: 56,
            columnSpacing: 24,
          ),
        ),
      );
    });
  }
}

Future<T?> push<T>(BuildContext context, Widget page) =>
    Navigator.of(context).push<T>(MaterialPageRoute(builder: (_) => page));
