import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:tms_core/tms_core.dart';

/// Everything the tenant app shows, loaded from the tenant portal API
/// (`/api/me/...`). The server only ever returns the signed-in tenant's records.
class TenantStore extends ChangeNotifier {
  TenantStore(this.session) {
    session.addListener(_sessionChanged);
    _sessionChanged();
  }

  final Session session;
  ApiClient get api => session.api;

  bool loaded = false;
  bool loading = false;
  String? loadError;

  Tenant? profile;
  Agreement? currentAgreement;
  double outstanding = 0;
  RentInvoice? nextDue;
  int openMaintenance = 0;
  final List<Agreement> agreements = [];
  final List<RentInvoice> invoices = [];
  final List<Payment> payments = [];
  final List<MaintenanceRequest> maintenance = [];
  final List<AppNotification> notifications = [];
  final List<TenantDocument> documents = [];
  int unread = 0;

  String? _loadedFor;
  Timer? _poll;

  List<RentInvoice> get unpaid => invoices.where((i) => i.status.isOpen).toList()
    ..sort((a, b) => a.dueDate.compareTo(b.dueDate));

  void _sessionChanged() {
    final me = session.me;
    if (!session.isSignedIn || me == null) {
      if (_loadedFor != null) _clear();
      return;
    }
    currencySymbol = me.currency;
    if (_loadedFor != me.userId && session.blocked == null && !me.mustChangePassword) {
      _loadedFor = me.userId;
      unawaited(refresh());
      _poll?.cancel();
      _poll = Timer.periodic(const Duration(minutes: 2), (_) => refreshNotifications());
    }
  }

  void _clear() {
    _poll?.cancel();
    _loadedFor = null;
    loaded = false;
    loadError = null;
    profile = null;
    currentAgreement = null;
    nextDue = null;
    outstanding = 0;
    for (final l in <List>[agreements, invoices, payments, maintenance, notifications, documents]) {
      l.clear();
    }
    notifyListeners();
  }

  Future<void> refresh() async {
    if (loading) return;
    loading = true;
    notifyListeners();
    try {
      final r = await Future.wait<dynamic>([
        api.get('/api/me/home'),
        api.get('/api/me/agreements'),
        api.get('/api/me/invoices'),
        api.get('/api/me/payments'),
        api.get('/api/me/maintenance'),
        api.get('/api/notifications'),
        api.get('/api/me/documents').catchError((_) => const []),
      ]);
      final home = asJson(r[0]);
      profile = Tenant.fromApi(asJson(home['tenant']));
      currentAgreement = home['currentAgreement'] == null ? null : Agreement.fromApi(asJson(home['currentAgreement']));
      outstanding = toDouble(home['outstanding']);
      nextDue = home['nextDue'] == null ? null : RentInvoice.fromApi(asJson(home['nextDue']));
      openMaintenance = (home['openMaintenance'] as num?)?.toInt() ?? 0;
      _set(agreements, asJsonList(r[1]).map(Agreement.fromApi));
      _set(invoices, asJsonList(asJson(r[2])['items']).map(RentInvoice.fromApi));
      invoices.sort((a, b) => b.billingMonth.compareTo(a.billingMonth));
      _set(payments, asJsonList(asJson(r[3])['items']).map(Payment.fromApi));
      payments.sort((a, b) => b.date.compareTo(a.date));
      _set(maintenance, asJsonList(r[4]).map(MaintenanceRequest.fromApi));
      maintenance.sort((a, b) => b.createdAt.compareTo(a.createdAt));
      _applyInbox(asJson(r[5]));
      _set(documents, asJsonList(r[6]).map(TenantDocument.fromApi));
      loaded = true;
      loadError = null;
    } on ApiException catch (e) {
      loadError = e.message;
    } finally {
      loading = false;
      notifyListeners();
    }
  }

  static void _set<T>(List<T> target, Iterable<T> items) => target
    ..clear()
    ..addAll(items);

  void _applyInbox(Json inbox) {
    unread = (inbox['unread'] as num?)?.toInt() ?? 0;
    _set(notifications, asJsonList(inbox['items']).map(AppNotification.fromApi));
  }

  Future<void> refreshNotifications() async {
    if (!session.isSignedIn || session.blocked != null) return;
    try {
      _applyInbox(asJson(await api.get('/api/notifications')));
      notifyListeners();
    } on ApiException {
      // Background poll.
    }
  }

  Future<void> markRead(AppNotification n) async {
    if (n.read) return;
    n.read = true;
    unread = (unread - 1).clamp(0, 1 << 30);
    notifyListeners();
    try {
      await api.post('/api/notifications/${n.id}/read');
    } on ApiException {
      // Ignore.
    }
  }

  Future<void> markAllRead() async {
    await api.post('/api/notifications/read-all');
    await refreshNotifications();
  }

  Future<Receipt> receipt(Payment p) async => Receipt.fromApi(asJson(await api.get('/api/me/payments/${p.id}/receipt')));

  Future<Download> receiptPdf(Payment p) => api.download('/api/me/payments/${p.id}/receipt.pdf');

  Future<Download> document(TenantDocument d) => api.download('/api/me/documents/${d.id}/file');

  /// Raises a maintenance request, optionally with a photo; returns the new request.
  Future<MaintenanceRequest> createMaintenance({
    required String title,
    required MaintenanceType type,
    required Priority priority,
    String description = '',
    List<int>? photo,
    String? photoName,
  }) async {
    final res = await api.post('/api/me/maintenance', {
      'tenantId': session.me!.tenantId,
      'title': title,
      'type': apiName(type),
      'priority': apiName(priority),
      'description': description,
    });
    var request = MaintenanceRequest.fromApi(asJson(res));
    if (photo != null && photoName != null) {
      request = MaintenanceRequest.fromApi(asJson(await api.upload('/api/me/maintenance/${request.id}/attachment',
          bytes: photo, fileName: photoName)));
    }
    await refresh();
    return request;
  }

  Future<Download> maintenanceAttachment(MaintenanceRequest r) =>
      api.download('/api/me/maintenance/${r.id}/attachment');

  @override
  void dispose() {
    _poll?.cancel();
    session.removeListener(_sessionChanged);
    super.dispose();
  }
}
