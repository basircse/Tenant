import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:tms_core/tms_core.dart';

/// Thrown by store operations when the server refuses a change.
class BusinessException implements Exception {
  BusinessException(this.message, {this.code});
  final String message;
  final String? code;
  @override
  String toString() => message;
}

/// The admin app's view of the organisation's data.
///
/// The server is the source of truth. The store keeps an in-memory copy of
/// everything the signed-in user may see (a landlord's portfolio is small:
/// hundreds of rows, not millions), so screens can filter, sort and join
/// locally. Every change goes to the API first and the copy is then reloaded.
class AppStore extends ChangeNotifier {
  AppStore(this.session) {
    session.addListener(_sessionChanged);
    _sessionChanged();
  }

  final Session session;
  ApiClient get api => session.api;

  bool loaded = false;
  bool loading = false;

  /// Last load error (shown as a banner); null when the copy is fresh.
  String? loadError;
  DateTime? lastLoaded;

  AppSettings settings = AppSettings();
  LicenseStatus? license;
  final List<AppUser> users = [];
  final List<Property> properties = [];
  final List<Unit> units = [];
  final List<Tenant> tenants = [];
  final List<Agreement> agreements = [];
  final List<RentInvoice> invoices = [];
  final List<Payment> payments = [];
  final List<MaintenanceRequest> maintenance = [];
  final List<AppNotification> notifications = [];
  final List<Expense> expenses = [];
  final List<AuditLog> auditLogs = [];
  int _unread = 0;

  AppUser? currentUser;
  String? _loadedFor;
  Timer? _poll;

  String get currency => settings.currency;

  // -------------------------------------------------------------------------
  // Loading
  // -------------------------------------------------------------------------

  void _sessionChanged() {
    final me = session.me;
    if (!session.isSignedIn || me == null) {
      if (_loadedFor != null) _clear();
      return;
    }
    currentUser = AppUser(
      id: me.userId,
      name: me.name,
      username: me.username,
      role: me.role,
      tenantId: me.tenantId,
      mustChangePassword: me.mustChangePassword,
    );
    if (_loadedFor != me.userId && session.blocked == null && !me.isVendor && !me.mustChangePassword) {
      _loadedFor = me.userId;
      unawaited(refresh());
      _poll?.cancel();
      // Picks up notifications and changes made on other devices.
      _poll = Timer.periodic(const Duration(minutes: 2), (_) => refreshNotifications());
    }
    notifyListeners();
  }

  void _clear() {
    _poll?.cancel();
    _loadedFor = null;
    loaded = false;
    loadError = null;
    currentUser = null;
    license = null;
    settings = AppSettings();
    for (final l in <List>[
      users,
      properties,
      units,
      tenants,
      agreements,
      invoices,
      payments,
      maintenance,
      notifications,
      expenses,
      auditLogs,
    ]) {
      l.clear();
    }
    notifyListeners();
  }

  /// Reloads everything from the server.
  Future<void> refresh() async {
    if (loading) return;
    loading = true;
    notifyListeners();
    try {
      final admin = isAdmin;
      final results = await Future.wait<dynamic>([
        api.get('/api/org'),
        api.get('/api/properties'),
        api.get('/api/units'),
        api.get('/api/tenants'),
        api.get('/api/agreements'),
        api.get('/api/rents'),
        api.get('/api/payments', query: {'includeVoided': true}),
        api.get('/api/maintenance'),
        api.get('/api/expenses'),
        api.get('/api/notifications'),
        admin ? api.get('/api/users') : Future.value(const []),
        admin ? api.get('/api/audit', query: {'limit': 500}) : Future.value(const []),
        admin ? api.get('/api/license') : Future.value(null),
      ]);
      settings = AppSettings.fromApi(asJson(results[0]));
      currencySymbol = settings.currency;
      _replace(properties, asJsonList(results[1]).map(Property.fromApi));
      _replace(units, asJsonList(results[2]).map(Unit.fromApi));
      _replace(tenants, asJsonList(results[3]).map(Tenant.fromApi));
      _replace(agreements, asJsonList(results[4]).map(Agreement.fromApi));
      _replace(invoices, asJsonList(results[5]['items']).map(RentInvoice.fromApi));
      _replace(payments, asJsonList(results[6]['items']).map(Payment.fromApi));
      _replace(maintenance, asJsonList(results[7]).map(MaintenanceRequest.fromApi));
      _replace(expenses, asJsonList(results[8]['items']).map(Expense.fromApi));
      _applyInbox(asJson(results[9]));
      _replace(users, asJsonList(results[10]).map(AppUser.fromApi));
      _replace(auditLogs, asJsonList(results[11]).map(AuditLog.fromApi));
      license = results[12] == null ? null : LicenseStatus.fromApi(asJson(results[12]));
      loaded = true;
      loadError = null;
      lastLoaded = DateTime.now();
    } on ApiException catch (e) {
      loadError = e.message;
    } finally {
      loading = false;
      notifyListeners();
    }
  }

  static void _replace<T>(List<T> target, Iterable<T> items) => target
    ..clear()
    ..addAll(items);

  void _applyInbox(Json inbox) {
    _unread = (inbox['unread'] as num?)?.toInt() ?? 0;
    _replace(notifications, asJsonList(inbox['items']).map(AppNotification.fromApi));
  }

  Future<void> refreshNotifications() async {
    if (!session.isSignedIn || session.blocked != null) return;
    try {
      _applyInbox(asJson(await api.get('/api/notifications')));
      notifyListeners();
    } on ApiException {
      // Background poll: ignore.
    }
  }

  /// Runs a change on the server, then reloads. Converts errors to [BusinessException].
  Future<T> _change<T>(Future<T> Function() call) async {
    try {
      final result = await call();
      await refresh();
      return result;
    } on ApiException catch (e) {
      throw BusinessException(e.message, code: e.code);
    }
  }

  // -------------------------------------------------------------------------
  // Notifications
  // -------------------------------------------------------------------------

  List<AppNotification> get myNotifications => notifications;

  int get unreadCount => _unread;

  Future<void> markNotificationRead(AppNotification n) async {
    if (n.read) return;
    n.read = true;
    _unread = (_unread - 1).clamp(0, 1 << 30);
    notifyListeners();
    try {
      await api.post('/api/notifications/${n.id}/read');
    } on ApiException {
      // Not important enough to bother the user.
    }
  }

  Future<void> markAllNotificationsRead() async {
    try {
      await api.post('/api/notifications/read-all');
    } on ApiException catch (e) {
      throw BusinessException(e.message);
    }
    await refreshNotifications();
  }

  // -------------------------------------------------------------------------
  // Role checks and data scoping (the server already filters by role)
  // -------------------------------------------------------------------------

  UserRole? get role => currentUser?.role;
  bool get isAdmin => role == UserRole.admin;
  bool get isStaff => role == UserRole.admin || role == UserRole.manager;
  bool get isTenant => role == UserRole.tenant;
  bool get isVendor => role == UserRole.vendor;

  bool canAccessProperty(String propertyId) => properties.any((p) => p.id == propertyId);

  List<Property> get visibleProperties => properties;
  List<Unit> get visibleUnits => units;
  List<Agreement> get visibleAgreements => agreements;
  List<Tenant> get visibleTenants => tenants;
  List<RentInvoice> get visibleInvoices => invoices;
  List<Payment> get visiblePayments => payments;
  List<MaintenanceRequest> get visibleMaintenance => maintenance;
  List<Expense> get visibleExpenses => expenses;

  // -------------------------------------------------------------------------
  // Lookups
  // -------------------------------------------------------------------------

  AppUser? userById(String? id) => users.where((e) => e.id == id).firstOrNull;
  Property? propertyById(String? id) => properties.where((e) => e.id == id).firstOrNull;
  Unit? unitById(String? id) => units.where((e) => e.id == id).firstOrNull;
  Tenant? tenantById(String? id) => tenants.where((e) => e.id == id).firstOrNull;
  Agreement? agreementById(String? id) => agreements.where((e) => e.id == id).firstOrNull;
  RentInvoice? invoiceById(String? id) => invoices.where((e) => e.id == id).firstOrNull;

  String propertyName(String? id) => propertyById(id)?.name ?? '-';
  String tenantName(String? id) => tenantById(id)?.name ?? '-';
  String unitLabel(String? id) => unitById(id)?.unitNo ?? '-';

  String unitFullLabel(String? id) {
    final u = unitById(id);
    if (u == null) return '-';
    return '${propertyName(u.propertyId)} • ${u.unitNo}';
  }

  List<Unit> unitsOf(String propertyId) {
    final list = units.where((u) => u.propertyId == propertyId).toList();
    list.sort((a, b) {
      final f = a.floor.compareTo(b.floor);
      return f != 0 ? f : a.unitNo.compareTo(b.unitNo);
    });
    return list;
  }

  Agreement? currentAgreementForTenant(String tenantId) =>
      agreements.where((a) => a.tenantId == tenantId && a.status.isCurrent).firstOrNull;

  Agreement? currentAgreementForUnit(String unitId) =>
      agreements.where((a) => a.unitId == unitId && a.status.isCurrent).firstOrNull;

  double tenantOutstanding(String tenantId) =>
      invoices.where((i) => i.tenantId == tenantId && i.status.isOpen).fold(0.0, (s, i) => s + i.dueAmount);

  List<RentInvoice> invoicesForTenant(String tenantId) => invoices.where((i) => i.tenantId == tenantId).toList()
    ..sort((a, b) => b.billingMonth.compareTo(a.billingMonth));

  List<Payment> paymentsForInvoice(String invoiceId) => payments.where((p) => p.invoiceId == invoiceId).toList();

  // -------------------------------------------------------------------------
  // Properties & units
  // -------------------------------------------------------------------------

  /// Creates (empty id) or updates a property; returns the saved property's id.
  Future<String> saveProperty(Property p) => _change(() async {
        final body = p.isNew
            ? await api.post('/api/properties', p.toApi())
            : await api.put('/api/properties/${p.id}', p.toApi());
        return str(body['property']['id']);
      });

  Future<void> togglePropertyStatus(Property p) => _change(() => api.post('/api/properties/${p.id}/status',
      {'status': p.status == RecordStatus.active ? 'INACTIVE' : 'ACTIVE'}));

  Future<void> saveUnit(Unit u) => _change(() => u.isNew
      ? api.post('/api/properties/${u.propertyId}/units', u.toApi())
      : api.put('/api/units/${u.id}', u.toApi()));

  Future<void> deleteUnit(Unit u) => _change(() => api.delete('/api/units/${u.id}'));

  // -------------------------------------------------------------------------
  // Tenants
  // -------------------------------------------------------------------------

  /// Creates or updates a tenant; returns the tenant's id.
  Future<String> saveTenant(Tenant t, {String? loginUsername, String? loginPassword}) => _change(() async {
        final body = t.toApi();
        if (t.isNew && loginUsername != null && loginUsername.isNotEmpty && loginPassword != null) {
          body['login'] = {'username': loginUsername.trim(), 'password': loginPassword};
        }
        final res = t.isNew ? await api.post('/api/tenants', body) : await api.put('/api/tenants/${t.id}', body);
        return str(res['tenant']['id']);
      });

  /// Full tenant record including documents and portal login.
  Future<TenantDetail> tenantDetail(String tenantId) async {
    try {
      return TenantDetail.fromApi(asJson(await api.get('/api/tenants/$tenantId')));
    } on ApiException catch (e) {
      throw BusinessException(e.message);
    }
  }

  Future<void> addTenantDocument(Tenant t, TenantDocument d) => _change(() => api.post(
      '/api/tenants/${t.id}/documents', {'name': d.name, 'docType': d.docType, 'reference': d.reference}));

  Future<void> uploadTenantDocument(Tenant t,
          {required String name,
          required String docType,
          String reference = '',
          required List<int> bytes,
          required String fileName}) =>
      _change(() => api.upload('/api/tenants/${t.id}/documents/upload',
          bytes: bytes, fileName: fileName, fields: {'name': name, 'docType': docType, 'reference': reference}));

  Future<Download> downloadTenantDocument(Tenant t, TenantDocument d) =>
      api.download('/api/tenants/${t.id}/documents/${d.id}/file');

  Future<void> removeTenantDocument(Tenant t, TenantDocument d) =>
      _change(() => api.delete('/api/tenants/${t.id}/documents/${d.id}'));

  // -------------------------------------------------------------------------
  // Agreements
  // -------------------------------------------------------------------------

  /// Creates a new agreement (draft or activated). Returns its id.
  Future<String> saveAgreement(Agreement a, {bool activate = false}) => _change(() async {
        final res = a.isNew
            ? await api.post('/api/agreements', a.toApi(activate: activate))
            : await api.put('/api/agreements/${a.id}', a.toApi(activate: false));
        final id = str(res['id']);
        if (!a.isNew && activate) await api.post('/api/agreements/$id/activate');
        return id;
      });

  Future<void> activateAgreement(Agreement a) => _change(() => api.post('/api/agreements/${a.id}/activate'));

  Future<void> terminateAgreement(Agreement a, String reason, {DateTime? date}) => _change(() => api.post(
      '/api/agreements/${a.id}/terminate', {'reason': reason, 'date': date == null ? null : isoDay(date)}));

  /// Renews an agreement and returns the new one.
  Future<Agreement> renewAgreement(Agreement old, {required DateTime newEnd, required double newRent}) async {
    final id = await _change(() async {
      final res = await api
          .post('/api/agreements/${old.id}/renew', {'newEndDate': isoDay(newEnd), 'monthlyRent': newRent});
      return str(res['id']);
    });
    return agreementById(id)!;
  }

  // -------------------------------------------------------------------------
  // Rent
  // -------------------------------------------------------------------------

  /// Generates invoices for [month]; returns how many were created.
  Future<int> generateRent(DateTime month) => _change(() async {
        final res = await api.post('/api/rents/generate', {'month': isoMonth(month)});
        return (res['created'] as num).toInt();
      });

  Future<void> cancelInvoice(RentInvoice inv, String reason) =>
      _change(() => api.post('/api/rents/${inv.id}/cancel', {'reason': reason}));

  // -------------------------------------------------------------------------
  // Payments
  // -------------------------------------------------------------------------

  Future<Payment> recordPayment({
    required RentInvoice invoice,
    required double amount,
    required DateTime date,
    required PaymentMethod method,
    String referenceNo = '',
    String remarks = '',
  }) async {
    final id = await _change(() async {
      final res = await api.post('/api/payments', {
        'invoiceId': invoice.id,
        'amount': amount,
        'paymentDate': isoDay(date),
        'method': apiName(method),
        'referenceNo': referenceNo.trim(),
        'remarks': remarks.trim(),
      });
      return str(res['receiptNo']); // the server answers with the receipt
    });
    return payments.firstWhere((p) => p.receiptNo == id);
  }

  Future<void> voidPayment(Payment p, String reason) =>
      _change(() => api.post('/api/payments/${p.id}/void', {'reason': reason}));

  Future<Receipt> receipt(Payment p) async {
    try {
      return Receipt.fromApi(asJson(await api.get('/api/payments/${p.id}/receipt')));
    } on ApiException catch (e) {
      throw BusinessException(e.message);
    }
  }

  Future<Download> receiptPdf(Payment p) => api.download('/api/payments/${p.id}/receipt.pdf');

  // -------------------------------------------------------------------------
  // Maintenance
  // -------------------------------------------------------------------------

  Future<MaintenanceRequest> createMaintenance({
    required String tenantId,
    required String unitId,
    required String title,
    required MaintenanceType type,
    required Priority priority,
    String description = '',
    String attachment = '',
  }) async {
    final id = await _change(() async {
      final res = await api.post('/api/maintenance', {
        'tenantId': tenantId,
        'title': title,
        'type': apiName(type),
        'priority': apiName(priority),
        'description': description,
        'attachment': attachment,
      });
      return str(res['id']);
    });
    return maintenance.firstWhere((m) => m.id == id);
  }

  Future<void> updateMaintenance(MaintenanceRequest r,
          {MaintenanceStatus? status, String? assignedTo, String note = ''}) =>
      _change(() async {
        if (assignedTo != null) {
          await api.post('/api/maintenance/${r.id}/assign', {'assignedTo': assignedTo.trim()});
        }
        if (status != null && status != r.status) {
          await api.post('/api/maintenance/${r.id}/status', {'status': apiName(status), 'note': note});
        }
      });

  Future<void> uploadMaintenanceAttachment(MaintenanceRequest r,
          {required List<int> bytes, required String fileName}) =>
      _change(() => api.upload('/api/maintenance/${r.id}/attachment', bytes: bytes, fileName: fileName));

  Future<Download> maintenanceAttachment(MaintenanceRequest r) =>
      api.download('/api/maintenance/${r.id}/attachment');

  // -------------------------------------------------------------------------
  // Expenses
  // -------------------------------------------------------------------------

  Future<void> saveExpense(Expense e) => _change(
      () => e.isNew ? api.post('/api/expenses', e.toApi()) : api.put('/api/expenses/${e.id}', e.toApi()));

  Future<void> deleteExpense(Expense e) => _change(() => api.delete('/api/expenses/${e.id}'));

  // -------------------------------------------------------------------------
  // Users (admin)
  // -------------------------------------------------------------------------

  Future<void> saveUser(AppUser u, {String? password}) => _change(() {
        final body = {
          'name': u.name,
          'username': u.username,
          'email': u.email,
          'mobile': u.mobile,
          'role': apiName(u.role),
          'propertyIds': u.assignedPropertyIds,
          'tenantId': u.tenantId,
          'password': password,
        };
        return u.id.isEmpty ? api.post('/api/users', body) : api.put('/api/users/${u.id}', body);
      });

  Future<void> setUserStatus(AppUser u, UserStatus status) =>
      _change(() => api.post('/api/users/${u.id}/status', {'status': apiName(status)}));

  /// Blocks a user of this organisation: signed out at once, cannot sign in until unblocked.
  Future<void> blockUser(AppUser u, String reason) =>
      _change(() => api.post('/api/users/${u.id}/block', {'reason': reason}));

  Future<void> unblockUser(AppUser u) => _change(() => api.post('/api/users/${u.id}/unblock'));

  /// Resets a user's password; returns the temporary password made by the server.
  Future<String> adminResetPassword(AppUser u) => _change(() async {
        final res = await api.post('/api/users/${u.id}/reset-password');
        return str(res['temporaryPassword']);
      });

  Future<void> updateSettings(AppSettings s) => _change(() => api.put('/api/org', s.toApi()));

  // -------------------------------------------------------------------------
  // Licence & devices (landlord admin)
  // -------------------------------------------------------------------------

  Future<List<DeviceInfoView>> devices() async {
    try {
      return asJsonList(await api.get('/api/devices')).map(DeviceInfoView.fromApi).toList();
    } on ApiException catch (e) {
      throw BusinessException(e.message);
    }
  }

  Future<void> removeDevice(DeviceInfoView d) => _change(() => api.delete('/api/devices/${d.id}'));

  Future<void> requestRenewal(String message) =>
      _change(() => api.post('/api/license/renewal-request', {'message': message}));

  /// The device this app is running on, as known to the server.
  String get deviceKey => session.device.key;

  @override
  void dispose() {
    _poll?.cancel();
    session.removeListener(_sessionChanged);
    super.dispose();
  }
}

/// `GET /api/tenants/{id}`: the tenant plus documents and portal login.
class TenantDetail {
  TenantDetail(this.tenant, this.documents, this.portalUsername, this.portalStatus);

  final Tenant tenant;
  final List<TenantDocument> documents;
  final String? portalUsername;
  final UserStatus? portalStatus;

  factory TenantDetail.fromApi(Json j) => TenantDetail(
        Tenant.fromApi(asJson(j['tenant'])),
        asJsonList(j['documents']).map(TenantDocument.fromApi).toList(),
        j['portalUsername'],
        j['portalStatus'] == null ? null : apiEnum(UserStatus.values, j['portalStatus'], UserStatus.active),
      );
}
