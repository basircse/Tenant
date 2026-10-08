import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:tms_core/tms_core.dart';

http.Response _json(Object body, [int status = 200]) =>
    http.Response(jsonEncode(body), status, headers: {'content-type': 'application/json'});

void main() {
  group('enum names', () {
    test('Dart names map to the server names and back', () {
      expect(apiName(RentStatus.partiallyPaid), 'PARTIALLY_PAID');
      expect(apiName(PaymentMethod.bankTransfer), 'BANK_TRANSFER');
      expect(apiName(MaintenanceStatus.inProgress), 'IN_PROGRESS');
      expect(apiEnum(RentStatus.values, 'PARTIALLY_PAID', RentStatus.pending), RentStatus.partiallyPaid);
      expect(apiEnum(LicenseState.values, 'GRACE', LicenseState.active), LicenseState.grace);
      expect(apiEnum(UserRole.values, 'unknown', UserRole.tenant), UserRole.tenant);
    });
  });

  group('models', () {
    test('invoice from the API computes balance and overdue days', () {
      final i = RentInvoice.fromApi({
        'id': 'a1',
        'code': 'INV-1',
        'tenantId': 't',
        'agreementId': 'g',
        'unitId': 'u',
        'propertyId': 'p',
        'billingMonth': '2026-10-01',
        'dueDate': '2026-10-05',
        'rentAmount': 10000,
        'serviceCharge': 3000,
        'utilityCharge': 2000,
        'otherCharge': 0,
        'paidAmount': 5000,
        'status': 'OVERDUE',
      });
      expect(i.totalAmount, 15000);
      expect(i.dueAmount, 10000);
      expect(i.status, RentStatus.overdue);
      expect(i.daysOverdue(DateTime(2026, 10, 15)), 10);
    });

    test('agreement round-trips dates without time zone shifts', () {
      final a = Agreement.fromApi({
        'id': 'x',
        'tenantId': 't',
        'unitId': 'u',
        'propertyId': 'p',
        'startDate': '2026-01-01',
        'endDate': '2026-12-31',
        'monthlyRent': 12000.5,
        'dueDay': 7,
        'status': 'EXPIRING',
      });
      expect(a.status.isCurrent, isTrue);
      final body = a.toApi(activate: true);
      expect(body['startDate'], '2026-01-01');
      expect(body['endDate'], '2026-12-31');
      expect(body['activate'], isTrue);
    });

    test('year-month values parse as the first day of the month', () {
      expect(parseDay('2026-10'), DateTime(2026, 10, 1));
      expect(isoMonth(DateTime(2026, 3, 9)), '2026-03');
    });
  });

  group('ApiClient', () {
    test('turns problem details into readable errors with codes', () async {
      final api = ApiClient(
        baseUrl: 'http://test',
        httpClient: MockClient((_) async => _json({
              'status': 403,
              'detail': 'The subscription has expired.',
              'code': 'LICENSE_EXPIRED',
            }, 403)),
      )..accessToken = 'a';
      ApiException? blocked;
      api.onBlocked = (e) => blocked = e;
      await expectLater(
        api.get('/api/properties'),
        throwsA(isA<ApiException>().having((e) => e.code, 'code', LicenseCodes.expired)),
      );
      expect(blocked?.message, 'The subscription has expired.');
    });

    test('validation errors list each field', () async {
      final api = ApiClient(
        baseUrl: 'http://test',
        httpClient: MockClient((_) async => _json({
              'detail': 'Validation failed',
              'errors': {'monthlyRent': 'must be greater than 0'},
            }, 400)),
      );
      await expectLater(
        api.post('/api/units', {}),
        throwsA(isA<ApiException>().having((e) => e.message, 'message', 'Monthly rent: must be greater than 0')),
      );
    });

    test('refreshes an expired access token once and retries', () async {
      var calls = 0;
      String? stored;
      final api = ApiClient(
        baseUrl: 'http://test',
        httpClient: MockClient((req) async {
          if (req.url.path == '/api/auth/refresh') {
            expect(jsonDecode(req.body)['refreshToken'], 'r1');
            return _json({'accessToken': 'a2', 'refreshToken': 'r2', 'user': {'userId': 'u'}});
          }
          calls++;
          if (req.headers['Authorization'] == 'Bearer a2') return _json([1, 2]);
          return _json({'detail': 'expired'}, 401);
        }),
      )
        ..accessToken = 'a1'
        ..refreshToken = 'r1';
      api.onRefreshTokenChanged = (t) => stored = t;
      expect(await api.get('/api/units'), [1, 2]);
      expect(calls, 2);
      expect(stored, 'r2');
    });

    test('a failed refresh ends the session', () async {
      String? ended;
      final api = ApiClient(
        baseUrl: 'http://test',
        httpClient: MockClient((req) async => _json({'detail': 'Session expired'}, 401)),
      )
        ..accessToken = 'a1'
        ..refreshToken = 'r1';
      api.onSessionExpired = (m) => ended = m;
      await expectLater(api.get('/api/units'), throwsA(isA<ApiException>()));
      expect(ended, isNotNull);
      expect(api.refreshToken, isNull);
    });

    test('network failures become status 0', () async {
      final api = ApiClient(
        baseUrl: 'http://test',
        httpClient: MockClient((_) async => throw http.ClientException('down')),
      );
      await expectLater(api.get('/x'), throwsA(isA<ApiException>().having((e) => e.isNetwork, 'network', isTrue)));
    });
  });

  group('updates', () {
    test('versions compare numerically', () {
      expect(compareVersions('1.10.0', '1.9.3'), 1);
      expect(compareVersions('1.0', '1.0.0'), 0);
      expect(compareVersions('1.0.0+5', '1.0.1'), -1);
    });

    test('below minimum must update; below latest may update', () {
      final r = AppRelease(latestVersion: '1.3.0', minVersion: '1.1.0');
      expect(updateStatus('1.0.9', r), UpdateStatus.required);
      expect(updateStatus('1.2.0', r), UpdateStatus.available);
      expect(updateStatus('1.3.0', r), UpdateStatus.none);
      expect(updateStatus('1.0.0', null), UpdateStatus.none);
    });
  });
}
