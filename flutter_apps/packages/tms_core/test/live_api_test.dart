// End-to-end check of the models against a running server.
//
// Skipped unless TMS_LIVE_URL is set, e.g. with the local dev server:
//   TMS_LIVE_URL=http://localhost:8080 TMS_VENDOR_PASSWORD=Vendor1234 flutter test test/live_api_test.dart
import 'dart:io';
import 'dart:math';

import 'package:flutter_test/flutter_test.dart';
import 'package:tms_core/tms_core.dart';

void main() {
  final url = Platform.environment['TMS_LIVE_URL'];
  final vendorUser = Platform.environment['TMS_VENDOR_USERNAME'] ?? 'vendor';
  final vendorPassword = Platform.environment['TMS_VENDOR_PASSWORD'] ?? 'Vendor1234';

  test('full landlord and tenant flow parses into the app models', () async {
    final suffix = Random().nextInt(1 << 30).toRadixString(36);
    const password = 'Secret123';

    Future<ApiClient> login(String user, String pw, {String device = 'live-test-device'}) async {
      final api = ApiClient(baseUrl: url!);
      final body = await api.post('/api/auth/login',
          {'identifier': user, 'password': pw, 'deviceKey': '$device-$suffix', 'deviceName': 'Test', 'platform': 'test'});
      api.applyTokens(asJson(body));
      expect(Me.fromApi(asJson(body['user'])).username, user);
      return api;
    }

    // Landlord registers and is locked until approved.
    final anon = ApiClient(baseUrl: url!);
    await anon.post('/api/auth/signup', {
      'organizationName': 'Live Estate $suffix',
      'name': 'Owner',
      'username': 'owner$suffix',
      'password': password,
      'mobile': '017${Random().nextInt(99999999).toString().padLeft(8, '0')}',
    });
    final owner = await login('owner$suffix', password);
    final me = Me.fromApi(asJson(await owner.get('/api/auth/me')));
    expect(me.licenseState, LicenseState.pending);
    await expectLater(owner.get('/api/properties'),
        throwsA(isA<ApiException>().having((e) => e.code, 'code', LicenseCodes.pending)));

    // Vendor approves.
    final vendor = await login(vendorUser, vendorPassword);
    final pending = asJsonList(await vendor.get('/api/vendor/organizations', query: {'state': 'PENDING'}));
    final org = pending.firstWhere((o) => o['name'] == 'Live Estate $suffix');
    await vendor.post('/api/vendor/organizations/${org['id']}/approve',
        {'expiresOn': isoDay(DateTime.now().add(const Duration(days: 365))), 'maxUnits': 10, 'maxDevices': 2, 'plan': 'Test'});
    final license = LicenseStatus.fromApi(asJson(await owner.get('/api/license')));
    expect(license.state, LicenseState.active);
    expect(license.maxUnits, 10);

    // Landlord sets up a flat and a tenant.
    final settings = AppSettings.fromApi(asJson(await owner.get('/api/org')));
    expect(settings.orgName, 'Live Estate $suffix');
    final prop = await owner.post('/api/properties',
        Property(name: 'Green View', type: PropertyType.apartment, address: 'Road 1', city: 'Dhaka', floors: 3).toApi());
    final propertyId = str(prop['property']['id']);
    await owner.post('/api/properties/$propertyId/units',
        Unit(propertyId: propertyId, floor: '1', unitNo: '1A', monthlyRent: 15000, serviceCharge: 2000).toApi());
    final units = asJsonList(await owner.get('/api/units')).map(Unit.fromApi).toList();
    expect(units.single.totalMonthly, 17000);
    final tenantBody = Tenant(name: 'Karim', mobile: '018${Random().nextInt(99999999).toString().padLeft(8, '0')}',
            nid: 'NID$suffix')
        .toApi()
      ..['login'] = {'username': 'karim$suffix', 'password': password};
    final tenantId = str((await owner.post('/api/tenants', tenantBody))['tenant']['id']);
    final now = DateTime.now();
    await owner.post(
        '/api/agreements',
        Agreement(
          tenantId: tenantId,
          unitId: units.single.id,
          propertyId: propertyId,
          startDate: DateTime(now.year, now.month, 1),
          endDate: DateTime(now.year + 1, now.month, 1).subtract(const Duration(days: 1)),
          monthlyRent: 15000,
          serviceCharge: 2000,
          dueDay: 5,
        ).toApi(activate: true));
    await owner.post('/api/rents/generate', {'month': isoMonth(now)});
    final invoices = asJsonList((await owner.get('/api/rents'))['items']).map(RentInvoice.fromApi).toList();
    expect(invoices.single.totalAmount, 17000);
    await owner.post('/api/payments', {
      'invoiceId': invoices.single.id,
      'amount': 7000,
      'paymentDate': isoDay(now),
      'method': apiName(PaymentMethod.bkash),
      'referenceNo': 'TX1',
    });
    final payments = asJsonList((await owner.get('/api/payments'))['items']).map(Payment.fromApi).toList();
    expect(payments.single.method, PaymentMethod.bkash);
    final receipt = Receipt.fromApi(asJson(await owner.get('/api/payments/${payments.single.id}/receipt')));
    expect(receipt.text, contains(payments.single.receiptNo));
    final pdf = await owner.download('/api/payments/${payments.single.id}/receipt.pdf');
    expect(String.fromCharCodes(pdf.bytes.take(4)), '%PDF');

    // Everything the admin app loads parses.
    asJsonList(await owner.get('/api/properties')).map(Property.fromApi).toList();
    asJsonList(await owner.get('/api/tenants')).map(Tenant.fromApi).toList();
    asJsonList(await owner.get('/api/agreements')).map(Agreement.fromApi).toList();
    asJsonList(await owner.get('/api/maintenance')).map(MaintenanceRequest.fromApi).toList();
    asJsonList((await owner.get('/api/expenses'))['items']).map(Expense.fromApi).toList();
    asJsonList(await owner.get('/api/users')).map(AppUser.fromApi).toList();
    asJsonList(await owner.get('/api/audit')).map(AuditLog.fromApi).toList();
    asJsonList(await owner.get('/api/devices')).map(DeviceInfoView.fromApi).toList();
    final report = asJson(await owner.get('/api/reports/collections'));
    expect(asJsonList(report['rows']), isNotEmpty);
    final xlsx = await owner.download('/api/reports/dues', query: {'format': 'xlsx'});
    expect(String.fromCharCodes(xlsx.bytes.take(2)), 'PK');

    // Tenant app view.
    final tenant = await login('karim$suffix', password, device: 'tenant-phone');
    final home = asJson(await tenant.get('/api/me/home'));
    expect(toDouble(home['outstanding']), 10000);
    expect(Agreement.fromApi(asJson(home['currentAgreement'])).unitNo, '1A');
    final created = MaintenanceRequest.fromApi(asJson(await tenant.post('/api/me/maintenance', {
      'tenantId': Tenant.fromApi(asJson(home['tenant'])).id,
      'title': 'Leaking tap',
      'type': 'PLUMBING',
      'priority': 'HIGH',
      'description': 'Kitchen tap drips all night',
    })));
    expect(created.priority, Priority.high);
    final inbox = asJson(await tenant.get('/api/notifications'));
    final titles = asJsonList(inbox['items']).map(AppNotification.fromApi).map((n) => n.title);
    expect(titles, contains('Payment received'));
    asJsonList(await tenant.get('/api/me/documents')).map(TenantDocument.fromApi).toList();
  }, skip: url == null ? 'Set TMS_LIVE_URL to run against a server' : false);
}
