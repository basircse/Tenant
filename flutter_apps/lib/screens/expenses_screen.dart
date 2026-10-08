import 'package:flutter/material.dart';

import '../widgets/common.dart';

class ExpensesScreen extends StatefulWidget {
  const ExpensesScreen({super.key});

  @override
  State<ExpensesScreen> createState() => _ExpensesScreenState();
}

class _ExpensesScreenState extends State<ExpensesScreen> {
  String? _propertyId;
  String? _category;
  DateTimeRange? _range;

  @override
  Widget build(BuildContext context) {
    final s = context.watchStore;
    final list = s.visibleExpenses.where((e) {
      if (_propertyId != null && e.propertyId != _propertyId) return false;
      if (_category != null && e.category != _category) return false;
      if (_range != null && (e.date.isBefore(_range!.start) || e.date.isAfter(_range!.end))) {
        return false;
      }
      return true;
    }).toList()
      ..sort((a, b) => b.date.compareTo(a.date));
    final total = list.fold(0.0, (a, e) => a + e.amount);

    return Scaffold(
      floatingActionButton: FloatingActionButton.extended(
        onPressed: () => _edit(context, null),
        icon: const Icon(Icons.add),
        label: Text(tr('Expense')),
      ),
      body: Column(children: [
        FilterBar(filters: [
          FilterDropdown<String>(
            label: tr('Property'),
            value: _propertyId,
            items: {for (final p in s.visibleProperties) p.id: p.name},
            onChanged: (v) => setState(() => _propertyId = v),
          ),
          FilterDropdown<String>(
            label: tr('Category'),
            value: _category,
            items: {for (final c in Expense.categories) c: Expense.categoryLabel(c)},
            onChanged: (v) => setState(() => _category = v),
          ),
          OutlinedButton.icon(
            icon: const Icon(Icons.date_range),
            label: Text(
                _range == null ? tr('Any date') : '${fmtDate(_range!.start)} – ${fmtDate(_range!.end)}'),
            onPressed: () async {
              final r = await showDateRangePicker(
                  context: context, firstDate: DateTime(2000), lastDate: DateTime(2100));
              setState(() => _range = r);
            },
          ),
        ]),
        Padding(
          padding: const EdgeInsets.fromLTRB(16, 4, 16, 0),
          child: Row(children: [
            Text(tr('Expenses: {n}', {'n': list.length})),
            const Spacer(),
            Text(tr('Total: {amount}', {'amount': fmtMoney(total, s.currency)}), style: const TextStyle(fontWeight: FontWeight.bold)),
          ]),
        ),
        Expanded(
          child: list.isEmpty
              ? EmptyState(message: tr('No expenses recorded'), icon: Icons.account_balance_wallet_outlined)
              : ListView.builder(
                  padding: const EdgeInsets.fromLTRB(12, 8, 12, 88),
                  itemCount: list.length,
                  itemBuilder: (c, i) {
                    final e = list[i];
                    return Card(
                      child: ListTile(
                        leading: const CircleAvatar(child: Icon(Icons.receipt_outlined)),
                        title: Text('${Expense.categoryLabel(e.category)} • ${fmtMoney(e.amount, s.currency)}'),
                        subtitle: Text(
                            '${fmtDate(e.date)} • ${e.propertyId == null ? tr('General') : s.propertyName(e.propertyId)}'
                            '${e.description.isNotEmpty ? '\n${e.description}' : ''}'),
                        trailing: PopupMenuButton<String>(
                          onSelected: (v) async {
                            if (v == 'edit') {
                              _edit(context, e);
                            } else if (await confirmDialog(context, tr('Delete expense'), tr('Delete this expense?'),
                                    destructive: true, confirm: tr('Delete')) &&
                                context.mounted) {
                              runGuarded(context, () => s.deleteExpense(e), success: tr('Expense deleted'));
                            }
                          },
                          itemBuilder: (_) => [
                            PopupMenuItem(value: 'edit', child: Text(tr('Edit'))),
                            PopupMenuItem(value: 'delete', child: Text(tr('Delete'))),
                          ],
                        ),
                      ),
                    );
                  },
                ),
        ),
      ]),
    );
  }

  Future<void> _edit(BuildContext context, Expense? e) async {
    final s = context.store;
    final amount = TextEditingController(text: e?.amount.toStringAsFixed(0));
    final desc = TextEditingController(text: e?.description);
    String? propertyId = e?.propertyId ?? (s.isAdmin ? null : s.visibleProperties.firstOrNull?.id);
    String category = e?.category ?? Expense.categories.first;
    DateTime date = e?.date ?? today();
    final key = GlobalKey<FormState>();
    final ok = await showDialog<bool>(
      context: context,
      builder: (c) => StatefulBuilder(
        builder: (c, setState) => AlertDialog(
          title: Text(e == null ? tr('New expense') : tr('Edit expense')),
          content: Form(
            key: key,
            child: SizedBox(
              width: 420,
              child: SingleChildScrollView(
                child: Column(mainAxisSize: MainAxisSize.min, children: [
                  AppDropdown<String?>(
                    label: tr('Property'),
                    value: propertyId,
                    items: {
                      if (s.isAdmin) null: tr('General (all properties)'),
                      for (final p in s.visibleProperties) p.id: p.name,
                    },
                    validator: (v) => !s.isAdmin && v == null ? tr('Required') : null,
                    onChanged: (v) => setState(() => propertyId = v),
                  ),
                  const SizedBox(height: 12),
                  AppDropdown<String>(
                    label: tr('Category'),
                    value: category,
                    items: {for (final x in Expense.categories) x: Expense.categoryLabel(x)},
                    onChanged: (v) => setState(() => category = v!),
                  ),
                  const SizedBox(height: 12),
                  AmountField(controller: amount, label: tr('Amount *'), required: true),
                  const SizedBox(height: 12),
                  DateField(label: tr('Date'), value: date, onChanged: (d) => setState(() => date = d!)),
                  const SizedBox(height: 12),
                  AppTextField(controller: desc, label: tr('Description')),
                ]),
              ),
            ),
          ),
          actions: [
            TextButton(onPressed: () => Navigator.pop(c, false), child: Text(tr('Cancel'))),
            FilledButton(
              onPressed: () {
                if (key.currentState!.validate()) Navigator.pop(c, true);
              },
              child: Text(tr('Save')),
            ),
          ],
        ),
      ),
    );
    if (ok != true || !context.mounted) return;
    final target = Expense(id: e?.id ?? '', date: date, category: category, amount: 0);
    target
      ..propertyId = propertyId
      ..category = category
      ..amount = parseAmount(amount.text)
      ..date = date
      ..description = desc.text.trim();
    runGuarded(context, () => s.saveExpense(target), success: tr('Expense saved'));
  }
}

