import 'package:flutter/material.dart';

import '../widgets/common.dart';
import 'agreements_screen.dart';
import 'tenants_screen.dart';

class PropertiesScreen extends StatefulWidget {
  const PropertiesScreen({super.key});

  @override
  State<PropertiesScreen> createState() => _PropertiesScreenState();
}

class _PropertiesScreenState extends State<PropertiesScreen> {
  String _q = '';
  PropertyType? _type;
  RecordStatus? _status;

  @override
  Widget build(BuildContext context) {
    final s = context.watchStore;
    final list = s.visibleProperties.where((p) {
      if (_type != null && p.type != _type) return false;
      if (_status != null && p.status != _status) return false;
      final q = _q.toLowerCase();
      return q.isEmpty ||
          p.name.toLowerCase().contains(q) ||
          p.code.toLowerCase().contains(q) ||
          p.address.toLowerCase().contains(q) ||
          p.city.toLowerCase().contains(q) ||
          p.ownerName.toLowerCase().contains(q);
    }).toList()
      ..sort((a, b) => a.name.compareTo(b.name));

    return Scaffold(
      floatingActionButton: FloatingActionButton.extended(
        onPressed: () => push(context, const PropertyFormScreen()),
        icon: const Icon(Icons.add),
        label: Text(tr('Property')),
      ),
      body: Column(
        children: [
          FilterBar(
            hint: tr('Search name, address, owner…'),
            onSearch: (v) => setState(() => _q = v),
            filters: [
              FilterDropdown<PropertyType>(
                label: tr('Type'),
                value: _type,
                items: {for (final t in PropertyType.values) t: t.label},
                onChanged: (v) => setState(() => _type = v),
              ),
              FilterDropdown<RecordStatus>(
                label: tr('Status'),
                value: _status,
                items: {for (final t in RecordStatus.values) t: t.label},
                onChanged: (v) => setState(() => _status = v),
              ),
            ],
          ),
          Expanded(
            child: list.isEmpty
                ? EmptyState(message: tr('No properties found'), icon: Icons.apartment)
                : ListView.builder(
                    padding: const EdgeInsets.fromLTRB(12, 8, 12, 88),
                    itemCount: list.length,
                    itemBuilder: (c, i) {
                      final p = list[i];
                      final units = s.unitsOf(p.id);
                      final occ = units.where((u) => u.status == UnitStatus.occupied).length;
                      final vac = units.where((u) => u.status == UnitStatus.available).length;
                      return Card(
                        child: ListTile(
                          leading: CircleAvatar(child: Icon(_typeIcon(p.type))),
                          title: Text(p.name),
                          subtitle: Text(
                              '${p.code} • ${p.type.label} • ${p.city}\n'
                              '${tr('Units: {n} • Occupied: {occupied} • Vacant: {vacant}', {'n': units.length, 'occupied': occ, 'vacant': vac})}'),
                          isThreeLine: true,
                          trailing: StatusChip(p.status),
                          onTap: () => push(context, PropertyDetailScreen(propertyId: p.id)),
                        ),
                      );
                    },
                  ),
          ),
        ],
      ),
    );
  }
}

IconData _typeIcon(PropertyType t) => switch (t) {
      PropertyType.residential => Icons.house_outlined,
      PropertyType.apartment => Icons.apartment,
      PropertyType.commercial => Icons.business,
      PropertyType.shop => Icons.storefront,
      PropertyType.office => Icons.corporate_fare,
      PropertyType.warehouse => Icons.warehouse_outlined,
    };

class PropertyDetailScreen extends StatelessWidget {
  const PropertyDetailScreen({super.key, required this.propertyId});

  final String propertyId;

  @override
  Widget build(BuildContext context) {
    final s = context.watchStore;
    final p = s.propertyById(propertyId);
    if (p == null) return Scaffold(body: EmptyState(message: tr('Property not found')));
    final units = s.unitsOf(p.id);
    final floors = <String, List<Unit>>{};
    for (final u in units) {
      floors.putIfAbsent(u.floor, () => []).add(u);
    }
    final occupied = units.where((u) => u.status == UnitStatus.occupied).length;
    final active = units.where((u) => u.status != UnitStatus.inactive).length;
    final rentRoll = units
        .where((u) => u.status == UnitStatus.occupied)
        .map((u) => s.currentAgreementForUnit(u.id)?.totalMonthly ?? 0)
        .fold(0.0, (a, b) => a + b);

    return Scaffold(
      appBar: AppBar(
        title: Text(p.name),
        actions: [
          IconButton(
            tooltip: tr('Edit'),
            icon: const Icon(Icons.edit),
            onPressed: () => push(context, PropertyFormScreen(property: p)),
          ),
          IconButton(
            tooltip: p.status == RecordStatus.active ? tr('Deactivate') : tr('Activate'),
            icon: Icon(p.status == RecordStatus.active ? Icons.block : Icons.check_circle_outline),
            onPressed: () async {
              final ok = await confirmDialog(
                  context,
                  p.status == RecordStatus.active ? tr('Deactivate property') : tr('Activate property'),
                  tr('Change status of {name}?', {'name': p.name}));
              if (ok && context.mounted) {
                runGuarded(context, () => s.togglePropertyStatus(p), success: tr('Status updated'));
              }
            },
          ),
        ],
      ),
      floatingActionButton: FloatingActionButton.extended(
        onPressed: () => push(context, UnitFormScreen(propertyId: p.id)),
        icon: const Icon(Icons.add),
        label: Text(tr('Unit')),
      ),
      body: ListView(
        padding: const EdgeInsets.fromLTRB(16, 16, 16, 88),
        children: [
          ResponsiveGrid(children: [
            StatCard(label: tr('Units'), value: '${units.length}', icon: Icons.door_front_door_outlined),
            StatCard(
                label: tr('Occupancy'),
                value: fmtPercent(active == 0 ? 0 : occupied * 100 / active),
                icon: Icons.pie_chart_outline),
            StatCard(
                label: tr('Monthly rent roll'), value: fmtMoney(rentRoll, s.currency), icon: Icons.payments),
          ]),
          const SizedBox(height: 8),
          InfoCard(title: tr('Property information'), trailing: StatusChip(p.status), children: [
            InfoRow(tr('Property ID'), p.code),
            InfoRow(tr('Type'), p.type.label),
            InfoRow(tr('Address'), p.address),
            InfoRow(tr('City'), p.city),
            InfoRow(tr('Owner'), p.ownerName),
            InfoRow(tr('Contact'), p.contactNumber),
            InfoRow(tr('Floors'), '${p.floors}'),
            InfoRow(tr('Description'), p.description),
          ]),
          SectionHeader(tr('Units ({n})', {'n': units.length})),
          if (units.isEmpty) EmptyState(message: tr('No units yet. Add the first unit.')),
          for (final floor in floors.entries) ...[
            Padding(
              padding: const EdgeInsets.fromLTRB(4, 8, 4, 4),
              child: Text(tr('Floor {floor}', {'floor': floor.key}), style: Theme.of(context).textTheme.labelLarge),
            ),
            ResponsiveGrid(
              minTileWidth: 260,
              children: [for (final u in floor.value) _UnitTile(unit: u)],
            ),
          ],
        ],
      ),
    );
  }
}

class _UnitTile extends StatelessWidget {
  const _UnitTile({required this.unit});

  final Unit unit;

  @override
  Widget build(BuildContext context) {
    final s = context.watchStore;
    final ag = s.currentAgreementForUnit(unit.id);
    return Card(
      child: InkWell(
        onTap: () => _showUnitSheet(context, unit),
        child: Padding(
          padding: const EdgeInsets.all(12),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(children: [
                Expanded(
                  child: Text(unit.unitNo,
                      style:
                          Theme.of(context).textTheme.titleMedium?.copyWith(fontWeight: FontWeight.bold)),
                ),
                StatusChip(unit.status),
              ]),
              Text('${unit.unitType} • ${unit.size}', style: Theme.of(context).textTheme.bodySmall),
              const SizedBox(height: 6),
              Text(tr('Rent {rent} • Total {total}', {
                'rent': fmtMoney(unit.monthlyRent, s.currency),
                'total': fmtMoney(unit.totalMonthly, s.currency),
              })),
              if (ag != null)
                Text(tr('Tenant: {name}', {'name': s.tenantName(ag.tenantId)}),
                    style: TextStyle(color: Theme.of(context).colorScheme.primary)),
            ],
          ),
        ),
      ),
    );
  }
}

void _showUnitSheet(BuildContext context, Unit unit) {
  final s = context.store;
  final ag = s.currentAgreementForUnit(unit.id);
  showModalBottomSheet(
    context: context,
    showDragHandle: true,
    builder: (c) => SafeArea(
      child: SingleChildScrollView(
        padding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(tr('Unit {unit}', {'unit': unit.unitNo}), style: Theme.of(c).textTheme.titleLarge),
            const SizedBox(height: 8),
            InfoRow(tr('Unit ID'), unit.code),
            InfoRow(tr('Floor'), unit.floor),
            InfoRow(tr('Type / Size'), '${unit.unitType} / ${unit.size}'),
            InfoRow(tr('Monthly rent'), fmtMoney(unit.monthlyRent, s.currency)),
            InfoRow(tr('Service charge'), fmtMoney(unit.serviceCharge, s.currency)),
            InfoRow(tr('Utility'), fmtMoney(unit.utilityCharge, s.currency)),
            InfoRow(tr('Other charge'), fmtMoney(unit.otherCharge, s.currency)),
            InfoRow(tr('Total monthly'), fmtMoney(unit.totalMonthly, s.currency)),
            InfoRow(tr('Security deposit'), fmtMoney(unit.securityDeposit, s.currency)),
            InfoRow(tr('Status'), unit.status.label),
            if (ag != null) InfoRow(tr('Current tenant'), s.tenantName(ag.tenantId)),
            const SizedBox(height: 12),
            Wrap(spacing: 8, runSpacing: 8, children: [
              OutlinedButton.icon(
                icon: const Icon(Icons.edit),
                label: Text(tr('Edit unit')),
                onPressed: () {
                  Navigator.pop(c);
                  push(context, UnitFormScreen(propertyId: unit.propertyId, unit: unit));
                },
              ),
              if (ag != null) ...[
                OutlinedButton.icon(
                  icon: const Icon(Icons.person),
                  label: Text(tr('View tenant')),
                  onPressed: () {
                    Navigator.pop(c);
                    push(context, TenantDetailScreen(tenantId: ag.tenantId));
                  },
                ),
                OutlinedButton.icon(
                  icon: const Icon(Icons.description),
                  label: Text(tr('View agreement')),
                  onPressed: () {
                    Navigator.pop(c);
                    push(context, AgreementDetailScreen(agreementId: ag.id));
                  },
                ),
              ],
              if (ag == null &&
                  (unit.status == UnitStatus.available || unit.status == UnitStatus.reserved))
                FilledButton.icon(
                  icon: const Icon(Icons.person_add),
                  label: Text(tr('Assign tenant')),
                  onPressed: () {
                    Navigator.pop(c);
                    push(context, AgreementFormScreen(unitId: unit.id));
                  },
                ),
              if (ag == null)
                TextButton.icon(
                  icon: const Icon(Icons.delete_outline),
                  label: Text(tr('Delete')),
                  onPressed: () async {
                    Navigator.pop(c);
                    if (await confirmDialog(context, tr('Delete unit'), tr('Delete unit {unit}?', {'unit': unit.unitNo}),
                            destructive: true, confirm: tr('Delete')) &&
                        context.mounted) {
                      runGuarded(context, () => s.deleteUnit(unit), success: tr('Unit deleted'));
                    }
                  },
                ),
            ]),
          ],
        ),
      ),
    ),
  );
}

// ---------------------------------------------------------------------------
// Forms
// ---------------------------------------------------------------------------

class PropertyFormScreen extends StatefulWidget {
  const PropertyFormScreen({super.key, this.property});

  final Property? property;

  @override
  State<PropertyFormScreen> createState() => _PropertyFormScreenState();
}

class _PropertyFormScreenState extends State<PropertyFormScreen> {
  final _key = GlobalKey<FormState>();
  late final p = widget.property;
  late final _name = TextEditingController(text: p?.name);
  late final _address = TextEditingController(text: p?.address);
  late final _city = TextEditingController(text: p?.city);
  late final _owner = TextEditingController(text: p?.ownerName);
  late final _contact = TextEditingController(text: p?.contactNumber);
  late final _floors = TextEditingController(text: '${p?.floors ?? 1}');
  late final _desc = TextEditingController(text: p?.description);
  late PropertyType _type = p?.type ?? PropertyType.residential;

  Future<void> _save() async {
    final s = context.store;
    final target = Property(id: p?.id ?? '', code: p?.code ?? '', name: '');
    target
      ..name = _name.text.trim()
      ..type = _type
      ..address = _address.text.trim()
      ..city = _city.text.trim()
      ..ownerName = _owner.text.trim()
      ..contactNumber = _contact.text.trim()
      ..floors = int.tryParse(latinDigits(_floors.text)) ?? 1
      ..description = _desc.text.trim();
    if (await runGuarded(context, () => s.saveProperty(target), success: tr('Property saved')) && mounted) {
      Navigator.pop(context);
    }
  }

  @override
  Widget build(BuildContext context) {
    return FormPage(
      title: p == null ? tr('New property') : tr('Edit property'),
      formKey: _key,
      onSave: _save,
      children: [
        FormGrid(children: [
          AppTextField(controller: _name, label: tr('Property name *'), validator: requiredValidator),
          AppDropdown<PropertyType>(
            label: tr('Property type *'),
            value: _type,
            items: {for (final t in PropertyType.values) t: t.label},
            onChanged: (v) => setState(() => _type = v!),
          ),
          AppTextField(controller: _address, label: tr('Address *'), validator: requiredValidator),
          AppTextField(controller: _city, label: tr('City *'), validator: requiredValidator),
          AppTextField(controller: _owner, label: tr('Owner name')),
          AppTextField(
              controller: _contact, label: tr('Contact number'), keyboardType: TextInputType.phone),
          AppTextField(
            controller: _floors,
            label: tr('Number of floors *'),
            keyboardType: TextInputType.number,
            validator: (v) => (int.tryParse(latinDigits(v ?? '')) ?? 0) < 1 ? tr('Enter at least 1') : null,
          ),
        ]),
        const SizedBox(height: 12),
        AppTextField(controller: _desc, label: tr('Description'), maxLines: 3),
      ],
    );
  }
}

class UnitFormScreen extends StatefulWidget {
  const UnitFormScreen({super.key, required this.propertyId, this.unit});

  final String propertyId;
  final Unit? unit;

  @override
  State<UnitFormScreen> createState() => _UnitFormScreenState();
}

class _UnitFormScreenState extends State<UnitFormScreen> {
  final _key = GlobalKey<FormState>();
  late final u = widget.unit;
  late final _floor = TextEditingController(text: u?.floor);
  late final _no = TextEditingController(text: u?.unitNo);
  late final _type = TextEditingController(text: u?.unitType ?? 'Flat');
  late final _size = TextEditingController(text: u?.size);
  late final _rent = TextEditingController(text: _n(u?.monthlyRent));
  late final _service = TextEditingController(text: _n(u?.serviceCharge));
  late final _utility = TextEditingController(text: _n(u?.utilityCharge));
  late final _other = TextEditingController(text: _n(u?.otherCharge));
  late final _deposit = TextEditingController(text: _n(u?.securityDeposit));
  late UnitStatus _status = u?.status ?? UnitStatus.available;

  static String _n(double? v) => v == null ? '' : v.toStringAsFixed(v % 1 == 0 ? 0 : 2);

  Future<void> _save() async {
    final s = context.store;
    final target = Unit(id: u?.id ?? '', code: u?.code ?? '', propertyId: widget.propertyId, floor: '', unitNo: '');
    target
      ..floor = _floor.text.trim()
      ..unitNo = _no.text.trim()
      ..unitType = _type.text.trim()
      ..size = _size.text.trim()
      ..monthlyRent = parseAmount(_rent.text)
      ..serviceCharge = parseAmount(_service.text)
      ..utilityCharge = parseAmount(_utility.text)
      ..otherCharge = parseAmount(_other.text)
      ..securityDeposit = parseAmount(_deposit.text)
      ..status = _status;
    final ok = await runGuarded(context, () => s.saveUnit(target), success: tr('Unit saved'));
    if (ok && mounted) Navigator.pop(context);
  }

  @override
  Widget build(BuildContext context) {
    final s = context.watchStore;
    final occupied = u?.status == UnitStatus.occupied;
    final statusItems = {
      for (final t in UnitStatus.values)
        if (t != UnitStatus.occupied || occupied) t: t.label,
    };
    return FormPage(
      title: u == null
          ? tr('New unit — {property}', {'property': s.propertyName(widget.propertyId)})
          : tr('Edit unit {unit}', {'unit': u!.unitNo}),
      formKey: _key,
      onSave: _save,
      children: [
        FormGrid(children: [
          AppTextField(controller: _floor, label: tr('Floor *'), validator: requiredValidator),
          AppTextField(controller: _no, label: tr('Unit number *'), validator: requiredValidator),
          AppTextField(controller: _type, label: tr('Unit type (Flat, Shop, Office…)')),
          AppTextField(controller: _size, label: tr('Size (e.g. 1200 sqft)')),
        ]),
        SectionHeader(tr('Rent configuration')),
        FormGrid(children: [
          AmountField(controller: _rent, label: tr('Monthly rent *'), required: true),
          AmountField(controller: _service, label: tr('Service charge')),
          AmountField(controller: _utility, label: tr('Utility')),
          AmountField(controller: _other, label: tr('Other charge')),
          AmountField(controller: _deposit, label: tr('Security deposit')),
          AppDropdown<UnitStatus>(
            label: tr('Status'),
            value: _status,
            items: statusItems,
            onChanged: occupied ? null : (v) => setState(() => _status = v!),
          ),
        ]),
        if (occupied)
          Padding(
            padding: const EdgeInsets.only(top: 8),
            child: Text(tr(
                'Status is managed automatically while the unit is occupied. Rent changes apply to new agreements.')),
          ),
      ],
    );
  }
}
