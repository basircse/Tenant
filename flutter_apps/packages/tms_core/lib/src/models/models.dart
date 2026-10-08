// Domain models shared by the admin and tenant apps.
//
// Every model is built from the server's JSON with `fromApi`. Server enums are
// UPPER_SNAKE_CASE (e.g. PARTIALLY_PAID); Dart enums use camelCase, and the
// helpers below convert between the two.

import '../l10n/i18n.dart';

/// Converts a Dart enum name to the server's name: `partiallyPaid` → `PARTIALLY_PAID`.
String apiName(Enum e) => e.name
    .replaceAllMapped(RegExp(r'[A-Z]'), (m) => '_${m[0]}')
    .toUpperCase();

T apiEnum<T extends Enum>(List<T> values, Object? name, T fallback) {
  if (name == null) return fallback;
  for (final v in values) {
    if (apiName(v) == name || v.name == name) return v;
  }
  return fallback;
}

DateTime? parseDate(Object? v) =>
    v == null ? null : DateTime.tryParse(v as String)?.toLocal();

/// Parses a date-only value ("2026-10-31") as a local calendar date.
DateTime? parseDay(Object? v) {
  if (v == null) return null;
  final s = v as String;
  // "2026-10" (YearMonth) → first day of the month.
  final full = s.length == 7 ? '$s-01' : s;
  final d = DateTime.tryParse(full);
  return d == null ? null : DateTime(d.year, d.month, d.day);
}

String isoDay(DateTime d) =>
    '${d.year.toString().padLeft(4, '0')}-${d.month.toString().padLeft(2, '0')}-${d.day.toString().padLeft(2, '0')}';

String isoMonth(DateTime d) =>
    '${d.year.toString().padLeft(4, '0')}-${d.month.toString().padLeft(2, '0')}';

double toDouble(Object? v) => v == null ? 0 : (v as num).toDouble();
String str(Object? v) => v == null ? '' : v.toString();

typedef Json = Map<String, dynamic>;

Json asJson(Object? v) => Map<String, dynamic>.from(v as Map);
List<Json> asJsonList(Object? v) =>
    ((v as List?) ?? const []).map((e) => asJson(e)).toList();

// ---------------------------------------------------------------------------
// Enums
// ---------------------------------------------------------------------------

enum UserRole {
  vendor, admin, manager, tenant;

  String get label => switch (this) {
        vendor => tr('Vendor'),
        admin => tr('Admin'),
        manager => tr('Property Manager'),
        tenant => tr('Tenant'),
      };
}

enum UserStatus {
  active, inactive, locked, blocked;

  String get label => switch (this) {
        active => tr('Active'),
        inactive => tr('Inactive'),
        locked => tr('Locked'),
        blocked => tr('Blocked'),
      };
}

enum PropertyType {
  residential, commercial, apartment, shop, office, warehouse;

  String get label => switch (this) {
        residential => tr('Residential'),
        commercial => tr('Commercial'),
        apartment => tr('Apartment'),
        shop => tr('Shop'),
        office => tr('Office'),
        warehouse => tr('Warehouse'),
      };
}

enum RecordStatus {
  active, inactive;

  String get label => switch (this) {
        active => tr('Active'),
        inactive => tr('Inactive'),
      };
}

enum UnitStatus {
  available, occupied, reserved, maintenance, inactive;

  String get label => switch (this) {
        available => tr('Available'),
        occupied => tr('Occupied'),
        reserved => tr('Reserved'),
        maintenance => tr('Maintenance'),
        inactive => tr('Inactive'),
      };
}

enum TenantStatus {
  active, inactive, previous;

  String get label => switch (this) {
        active => tr('Active'),
        inactive => tr('Inactive'),
        previous => tr('Previous Tenant'),
      };
}

enum AgreementStatus {
  draft, active, expiring, expired, terminated;

  String get label => switch (this) {
        draft => tr('Draft'),
        active => tr('Active'),
        expiring => tr('Expiring'),
        expired => tr('Expired'),
        terminated => tr('Terminated'),
      };

  bool get isCurrent => this == active || this == expiring;
}

enum RentStatus {
  pending, partiallyPaid, paid, overdue, cancelled;

  String get label => switch (this) {
        pending => tr('Pending'),
        partiallyPaid => tr('Partially Paid'),
        paid => tr('Paid'),
        overdue => tr('Overdue'),
        cancelled => tr('Cancelled'),
      };

  bool get isOpen => this == pending || this == partiallyPaid || this == overdue;
}

enum PaymentMethod {
  cash, bankTransfer, bkash, nagad, other;

  String get label => switch (this) {
        cash => tr('Cash'),
        bankTransfer => tr('Bank Transfer'),
        bkash => tr('bKash'),
        nagad => tr('Nagad'),
        other => tr('Other'),
      };
}

enum MaintenanceType {
  electrical, plumbing, gas, lift, ac, water, general;

  String get label => switch (this) {
        electrical => tr('Electrical'),
        plumbing => tr('Plumbing'),
        gas => tr('Gas'),
        lift => tr('Lift'),
        ac => tr('AC'),
        water => tr('Water'),
        general => tr('General'),
      };
}

enum Priority {
  low, medium, high, urgent;

  String get label => switch (this) {
        low => tr('Low'),
        medium => tr('Medium'),
        high => tr('High'),
        urgent => tr('Urgent'),
      };
}

enum MaintenanceStatus {
  open, assigned, inProgress, completed, closed;

  String get label => switch (this) {
        open => tr('Open'),
        assigned => tr('Assigned'),
        inProgress => tr('In Progress'),
        completed => tr('Completed'),
        closed => tr('Closed'),
      };

  MaintenanceStatus? get next {
    final i = index + 1;
    return i < MaintenanceStatus.values.length ? MaintenanceStatus.values[i] : null;
  }

  bool get isPending => this != completed && this != closed;
}

/// Licence state of the landlord's organisation, as reported by the server.
enum LicenseState {
  pending, rejected, suspended, active, grace, expired;

  String get label => switch (this) {
        pending => tr('Waiting for approval'),
        rejected => tr('Rejected'),
        suspended => tr('Suspended'),
        active => tr('Active'),
        grace => tr('Expired (read-only)'),
        expired => tr('Expired'),
      };

  bool get usable => this == active || this == grace;
}

enum DeviceStatus {
  approved, pending, blocked;

  String get label => switch (this) {
        approved => tr('Approved'),
        pending => tr('Pending'),
        blocked => tr('Blocked'),
      };
}

// ---------------------------------------------------------------------------
// Session
// ---------------------------------------------------------------------------

/// The signed-in user (`GET /api/auth/me`).
class Me {
  Me({
    required this.userId,
    required this.name,
    required this.username,
    required this.role,
    required this.orgId,
    required this.orgName,
    required this.orgStatus,
    required this.licenseState,
    required this.currency,
    this.tenantId,
    this.mustChangePassword = false,
  });

  final String userId;
  final String name;
  final String username;
  final UserRole role;
  final String orgId;
  final String orgName;
  final String orgStatus;
  final LicenseState licenseState;
  final String currency;
  final String? tenantId;
  final bool mustChangePassword;

  bool get isVendor => role == UserRole.vendor;
  bool get isTenant => role == UserRole.tenant;
  bool get isStaff => role == UserRole.admin || role == UserRole.manager;

  factory Me.fromApi(Json j) => Me(
        userId: str(j['userId']),
        name: str(j['name']),
        username: str(j['username']),
        role: apiEnum(UserRole.values, j['role'], UserRole.tenant),
        orgId: str(j['orgId']),
        orgName: str(j['orgName']),
        orgStatus: str(j['orgStatus']),
        licenseState: apiEnum(LicenseState.values, j['licenseState'], LicenseState.active),
        currency: j['currency'] ?? '৳',
        tenantId: j['tenantId'],
        mustChangePassword: j['mustChangePassword'] ?? false,
      );
}

/// `GET /api/license`.
class LicenseStatus {
  LicenseStatus({
    required this.state,
    this.plan,
    this.expiresOn,
    this.daysLeft,
    this.graceEndsOn,
    this.readOnly = false,
    this.maxUnits,
    this.unitsUsed = 0,
    this.maxDevices,
    this.devicesUsed = 0,
    this.message = '',
  });

  final LicenseState state;
  final String? plan;
  final DateTime? expiresOn;
  final int? daysLeft;
  final DateTime? graceEndsOn;
  final bool readOnly;
  final int? maxUnits;
  final int unitsUsed;
  final int? maxDevices;
  final int devicesUsed;
  final String message;

  factory LicenseStatus.fromApi(Json j) => LicenseStatus(
        state: apiEnum(LicenseState.values, j['state'], LicenseState.pending),
        plan: j['plan'],
        expiresOn: parseDay(j['expiresOn']),
        daysLeft: (j['daysLeft'] as num?)?.toInt(),
        graceEndsOn: parseDay(j['graceEndsOn']),
        readOnly: j['readOnly'] ?? false,
        maxUnits: (j['maxUnits'] as num?)?.toInt(),
        unitsUsed: (j['unitsUsed'] as num?)?.toInt() ?? 0,
        maxDevices: (j['maxDevices'] as num?)?.toInt(),
        devicesUsed: (j['devicesUsed'] as num?)?.toInt() ?? 0,
        message: str(j['message']),
      );
}

/// A device registered against the organisation (admin app installs).
class DeviceInfoView {
  DeviceInfoView({
    required this.id,
    required this.app,
    required this.name,
    required this.platform,
    required this.status,
    this.lastSeenAt,
    this.registeredAt,
  });

  final String id;
  final String app;
  final String name;
  final String platform;
  final DeviceStatus status;
  final DateTime? lastSeenAt;
  final DateTime? registeredAt;

  factory DeviceInfoView.fromApi(Json j) => DeviceInfoView(
        id: str(j['id']),
        app: str(j['app']),
        name: str(j['name']),
        platform: str(j['platform']),
        status: apiEnum(DeviceStatus.values, j['status'], DeviceStatus.pending),
        lastSeenAt: parseDate(j['lastSeenAt']),
        registeredAt: parseDate(j['registeredAt']),
      );
}

// ---------------------------------------------------------------------------
// Business records
// ---------------------------------------------------------------------------

class AppUser {
  AppUser({
    required this.id,
    required this.name,
    required this.username,
    required this.role,
    this.email = '',
    this.mobile = '',
    this.status = UserStatus.active,
    this.tenantId,
    List<String>? assignedPropertyIds,
    this.mustChangePassword = false,
    DateTime? createdAt,
    this.blockedReason = '',
    this.blockedBy = '',
    this.blockedAt,
    this.blockedByVendor = false,
  })  : assignedPropertyIds = assignedPropertyIds ?? [],
        createdAt = createdAt ?? DateTime.now();

  String id;
  String name;
  String email;
  String mobile;
  String username;
  UserRole role;
  UserStatus status;
  String? tenantId;
  List<String> assignedPropertyIds;
  bool mustChangePassword;
  DateTime createdAt;

  /// Set while [status] is [UserStatus.blocked].
  String blockedReason;
  String blockedBy;
  DateTime? blockedAt;

  /// Blocked by the service provider: only they can unblock.
  bool blockedByVendor;

  bool get isStaff => role == UserRole.admin || role == UserRole.manager;

  factory AppUser.fromApi(Json j) => AppUser(
        id: str(j['id']),
        name: str(j['name']),
        username: str(j['username']),
        email: str(j['email']),
        mobile: str(j['mobile']),
        role: apiEnum(UserRole.values, j['role'], UserRole.manager),
        status: apiEnum(UserStatus.values, j['status'], UserStatus.active),
        tenantId: j['tenantId'],
        assignedPropertyIds: List<String>.from(j['propertyIds'] ?? const []),
        mustChangePassword: j['mustChangePassword'] ?? false,
        createdAt: parseDate(j['createdAt']),
        blockedReason: str(j['blockedReason']),
        blockedBy: str(j['blockedBy']),
        blockedAt: parseDate(j['blockedAt']),
        blockedByVendor: j['blockedByVendor'] ?? false,
      );
}

class Property {
  Property({
    this.id = '',
    this.code = '',
    required this.name,
    this.type = PropertyType.residential,
    this.address = '',
    this.city = '',
    this.ownerName = '',
    this.contactNumber = '',
    this.floors = 1,
    this.description = '',
    this.status = RecordStatus.active,
    this.unitCount = 0,
    this.occupiedCount = 0,
  });

  String id;
  String code;
  String name;
  PropertyType type;
  String address;
  String city;
  String ownerName;
  String contactNumber;
  int floors;
  String description;
  RecordStatus status;
  int unitCount;
  int occupiedCount;

  bool get isNew => id.isEmpty;

  factory Property.fromApi(Json j) => Property(
        id: str(j['id']),
        code: str(j['code']),
        name: str(j['name']),
        type: apiEnum(PropertyType.values, j['type'], PropertyType.residential),
        address: str(j['address']),
        city: str(j['city']),
        ownerName: str(j['ownerName']),
        contactNumber: str(j['contactNumber']),
        floors: (j['floors'] as num?)?.toInt() ?? 1,
        description: str(j['description']),
        status: apiEnum(RecordStatus.values, j['status'], RecordStatus.active),
        unitCount: (j['units'] as num?)?.toInt() ?? 0,
        occupiedCount: (j['occupied'] as num?)?.toInt() ?? 0,
      );

  Json toApi() => {
        'name': name,
        'type': apiName(type),
        'address': address,
        'city': city,
        'ownerName': ownerName,
        'contactNumber': contactNumber,
        'floors': floors,
        'description': description,
      };

  Property copy() => Property.fromApi({...toApi(), 'id': id, 'code': code, 'status': apiName(status)});
}

class Unit {
  Unit({
    this.id = '',
    this.code = '',
    required this.propertyId,
    this.propertyName = '',
    required this.floor,
    required this.unitNo,
    this.unitType = 'Flat',
    this.size = '',
    this.monthlyRent = 0,
    this.serviceCharge = 0,
    this.utilityCharge = 0,
    this.otherCharge = 0,
    this.securityDeposit = 0,
    this.status = UnitStatus.available,
    this.currentTenantId,
    this.currentTenantName,
  });

  String id;
  String code;
  String propertyId;
  String propertyName;
  String floor;
  String unitNo;
  String unitType;
  String size;
  double monthlyRent;
  double serviceCharge;
  double utilityCharge;
  double otherCharge;
  double securityDeposit;
  UnitStatus status;
  String? currentTenantId;
  String? currentTenantName;

  bool get isNew => id.isEmpty;

  double get totalMonthly => monthlyRent + serviceCharge + utilityCharge + otherCharge;

  factory Unit.fromApi(Json j) => Unit(
        id: str(j['id']),
        code: str(j['code']),
        propertyId: str(j['propertyId']),
        propertyName: str(j['propertyName']),
        floor: str(j['floor']),
        unitNo: str(j['unitNo']),
        unitType: str(j['unitType']),
        size: str(j['size']),
        monthlyRent: toDouble(j['monthlyRent']),
        serviceCharge: toDouble(j['serviceCharge']),
        utilityCharge: toDouble(j['utilityCharge']),
        otherCharge: toDouble(j['otherCharge']),
        securityDeposit: toDouble(j['securityDeposit']),
        status: apiEnum(UnitStatus.values, j['status'], UnitStatus.available),
        currentTenantId: j['currentTenantId'],
        currentTenantName: j['currentTenantName'],
      );

  Json toApi() => {
        'floor': floor,
        'unitNo': unitNo,
        'unitType': unitType,
        'size': size,
        'monthlyRent': monthlyRent,
        'serviceCharge': serviceCharge,
        'utilityCharge': utilityCharge,
        'otherCharge': otherCharge,
        'securityDeposit': securityDeposit,
        'status': apiName(status),
      };
}

class TenantDocument {
  TenantDocument({
    this.id = '',
    required this.name,
    this.docType = 'Other',
    this.reference = '',
    this.hasFile = false,
    this.contentType,
    this.sizeBytes,
    DateTime? addedAt,
  }) : addedAt = addedAt ?? DateTime.now();

  String id;
  String name;
  String docType;
  String reference;
  bool hasFile;
  String? contentType;
  int? sizeBytes;
  DateTime addedAt;

  factory TenantDocument.fromApi(Json j) => TenantDocument(
        id: str(j['id']),
        name: str(j['name']),
        docType: str(j['docType']),
        reference: str(j['reference']),
        hasFile: j['hasFile'] ?? false,
        contentType: j['contentType'],
        sizeBytes: (j['sizeBytes'] as num?)?.toInt(),
        addedAt: parseDate(j['addedAt']),
      );
}

class Tenant {
  Tenant({
    this.id = '',
    this.code = '',
    required this.name,
    required this.mobile,
    this.email = '',
    this.nid = '',
    this.dateOfBirth,
    this.presentAddress = '',
    this.emergencyContact = '',
    this.occupation = '',
    this.status = TenantStatus.inactive,
    List<TenantDocument>? documents,
    this.outstanding = 0,
    DateTime? createdAt,
  })  : documents = documents ?? [],
        createdAt = createdAt ?? DateTime.now();

  String id;
  String code;
  String name;
  String mobile;
  String email;
  String nid;
  DateTime? dateOfBirth;
  String presentAddress;
  String emergencyContact;
  String occupation;
  TenantStatus status;
  List<TenantDocument> documents;
  double outstanding;
  DateTime createdAt;

  bool get isNew => id.isEmpty;

  String get initials {
    final parts = name.trim().split(RegExp(r'\s+')).where((p) => p.isNotEmpty).toList();
    if (parts.isEmpty) return '?';
    if (parts.length == 1) return parts.first[0].toUpperCase();
    return (parts.first[0] + parts.last[0]).toUpperCase();
  }

  factory Tenant.fromApi(Json j) => Tenant(
        id: str(j['id']),
        code: str(j['code']),
        name: str(j['name']),
        mobile: str(j['mobile']),
        email: str(j['email']),
        nid: str(j['nid']),
        dateOfBirth: parseDay(j['dateOfBirth']),
        presentAddress: str(j['presentAddress']),
        emergencyContact: str(j['emergencyContact']),
        occupation: str(j['occupation']),
        status: apiEnum(TenantStatus.values, j['status'], TenantStatus.inactive),
        outstanding: toDouble(j['outstanding']),
        createdAt: parseDate(j['createdAt']),
      );

  Json toApi() => {
        'name': name,
        'mobile': mobile,
        'email': email,
        'nid': nid,
        'dateOfBirth': dateOfBirth == null ? null : isoDay(dateOfBirth!),
        'presentAddress': presentAddress,
        'emergencyContact': emergencyContact,
        'occupation': occupation,
        'status': apiName(status),
      };
}

class Agreement {
  Agreement({
    this.id = '',
    this.code = '',
    required this.tenantId,
    required this.unitId,
    required this.propertyId,
    required this.startDate,
    required this.endDate,
    required this.monthlyRent,
    this.tenantName = '',
    this.unitNo = '',
    this.propertyName = '',
    this.serviceCharge = 0,
    this.utilityCharge = 0,
    this.otherCharge = 0,
    this.securityDeposit = 0,
    this.advanceAmount = 0,
    this.dueDay = 5,
    this.documentRef = '',
    this.terms = '',
    this.status = AgreementStatus.draft,
    this.renewedFromId,
    this.terminatedAt,
    this.terminationReason = '',
  });

  String id;
  String code;
  String tenantId;
  String unitId;
  String propertyId;
  String tenantName;
  String unitNo;
  String propertyName;
  DateTime startDate;
  DateTime endDate;
  double monthlyRent;
  double serviceCharge;
  double utilityCharge;
  double otherCharge;
  double securityDeposit;
  double advanceAmount;
  int dueDay;
  String documentRef;
  String terms;
  AgreementStatus status;
  String? renewedFromId;
  DateTime? terminatedAt;
  String terminationReason;

  bool get isNew => id.isEmpty;

  double get totalMonthly => monthlyRent + serviceCharge + utilityCharge + otherCharge;

  factory Agreement.fromApi(Json j) => Agreement(
        id: str(j['id']),
        code: str(j['code']),
        tenantId: str(j['tenantId']),
        unitId: str(j['unitId']),
        propertyId: str(j['propertyId']),
        tenantName: str(j['tenantName']),
        unitNo: str(j['unitNo']),
        propertyName: str(j['propertyName']),
        startDate: parseDay(j['startDate'])!,
        endDate: parseDay(j['endDate'])!,
        monthlyRent: toDouble(j['monthlyRent']),
        serviceCharge: toDouble(j['serviceCharge']),
        utilityCharge: toDouble(j['utilityCharge']),
        otherCharge: toDouble(j['otherCharge']),
        securityDeposit: toDouble(j['securityDeposit']),
        advanceAmount: toDouble(j['advanceAmount']),
        dueDay: (j['dueDay'] as num?)?.toInt() ?? 5,
        documentRef: str(j['documentRef']),
        terms: str(j['terms']),
        status: apiEnum(AgreementStatus.values, j['status'], AgreementStatus.draft),
        renewedFromId: j['renewedFromId'],
        terminatedAt: parseDay(j['terminatedOn']),
        terminationReason: str(j['terminationReason']),
      );

  Json toApi({required bool activate}) => {
        'tenantId': tenantId,
        'unitId': unitId,
        'startDate': isoDay(startDate),
        'endDate': isoDay(endDate),
        'monthlyRent': monthlyRent,
        'serviceCharge': serviceCharge,
        'utilityCharge': utilityCharge,
        'otherCharge': otherCharge,
        'securityDeposit': securityDeposit,
        'advanceAmount': advanceAmount,
        'dueDay': dueDay,
        'documentRef': documentRef,
        'terms': terms,
        'activate': activate,
      };
}

class RentInvoice {
  RentInvoice({
    required this.id,
    this.code = '',
    required this.tenantId,
    required this.agreementId,
    required this.unitId,
    required this.propertyId,
    required this.billingMonth,
    required this.dueDate,
    required this.rentAmount,
    this.tenantName = '',
    this.unitNo = '',
    this.propertyName = '',
    this.serviceCharge = 0,
    this.utilityCharge = 0,
    this.otherCharge = 0,
    this.paidAmount = 0,
    this.status = RentStatus.pending,
    this.cancelReason = '',
  });

  String id;
  String code;
  String tenantId;
  String agreementId;
  String unitId;
  String propertyId;
  String tenantName;
  String unitNo;
  String propertyName;
  DateTime billingMonth;
  DateTime dueDate;
  double rentAmount;
  double serviceCharge;
  double utilityCharge;
  double otherCharge;
  double paidAmount;
  RentStatus status;
  String cancelReason;

  double get totalAmount => rentAmount + serviceCharge + utilityCharge + otherCharge;
  double get dueAmount =>
      status == RentStatus.cancelled ? 0 : (totalAmount - paidAmount).clamp(0, double.infinity);

  int daysOverdue(DateTime today) {
    if (!status.isOpen) return 0;
    final d = DateTime(today.year, today.month, today.day).difference(dueDate).inDays;
    return d > 0 ? d : 0;
  }

  factory RentInvoice.fromApi(Json j) => RentInvoice(
        id: str(j['id']),
        code: str(j['code']),
        tenantId: str(j['tenantId']),
        agreementId: str(j['agreementId']),
        unitId: str(j['unitId']),
        propertyId: str(j['propertyId']),
        tenantName: str(j['tenantName']),
        unitNo: str(j['unitNo']),
        propertyName: str(j['propertyName']),
        billingMonth: parseDay(j['billingMonth'])!,
        dueDate: parseDay(j['dueDate'])!,
        rentAmount: toDouble(j['rentAmount']),
        serviceCharge: toDouble(j['serviceCharge']),
        utilityCharge: toDouble(j['utilityCharge']),
        otherCharge: toDouble(j['otherCharge']),
        paidAmount: toDouble(j['paidAmount']),
        status: apiEnum(RentStatus.values, j['status'], RentStatus.pending),
        cancelReason: str(j['cancelReason']),
      );
}

class Payment {
  Payment({
    required this.id,
    this.code = '',
    required this.receiptNo,
    required this.invoiceId,
    required this.tenantId,
    required this.unitId,
    required this.propertyId,
    required this.date,
    required this.amount,
    required this.method,
    this.invoiceCode = '',
    this.billingMonth,
    this.tenantName = '',
    this.unitNo = '',
    this.propertyName = '',
    this.referenceNo = '',
    this.remarks = '',
    this.recordedBy = '',
    this.voided = false,
    this.voidReason = '',
  });

  String id;
  String code;
  String receiptNo;
  String invoiceId;
  String invoiceCode;
  DateTime? billingMonth;
  String tenantId;
  String unitId;
  String propertyId;
  String tenantName;
  String unitNo;
  String propertyName;
  DateTime date;
  double amount;
  PaymentMethod method;
  String referenceNo;
  String remarks;
  String recordedBy;
  bool voided;
  String voidReason;

  factory Payment.fromApi(Json j) => Payment(
        id: str(j['id']),
        code: str(j['code']),
        receiptNo: str(j['receiptNo']),
        invoiceId: str(j['invoiceId']),
        invoiceCode: str(j['invoiceCode']),
        billingMonth: parseDay(j['billingMonth']),
        tenantId: str(j['tenantId']),
        unitId: str(j['unitId']),
        propertyId: str(j['propertyId']),
        tenantName: str(j['tenantName']),
        unitNo: str(j['unitNo']),
        propertyName: str(j['propertyName']),
        date: parseDay(j['paymentDate'])!,
        amount: toDouble(j['amount']),
        method: apiEnum(PaymentMethod.values, j['method'], PaymentMethod.cash),
        referenceNo: str(j['referenceNo']),
        remarks: str(j['remarks']),
        recordedBy: str(j['recordedBy']),
        voided: j['voided'] ?? false,
        voidReason: str(j['voidReason']),
      );
}

/// A printable payment receipt (`GET /api/payments/{id}/receipt`).
class Receipt {
  Receipt({required this.receiptNo, required this.text, required this.voided});

  final String receiptNo;
  final String text;
  final bool voided;

  factory Receipt.fromApi(Json j) =>
      Receipt(receiptNo: str(j['receiptNo']), text: str(j['text']), voided: j['voided'] ?? false);
}

class StatusChange {
  StatusChange({required this.status, required this.at, required this.by, this.note = ''});

  MaintenanceStatus status;
  DateTime at;
  String by;
  String note;

  factory StatusChange.fromApi(Json j) => StatusChange(
        status: apiEnum(MaintenanceStatus.values, j['status'], MaintenanceStatus.open),
        at: parseDate(j['at']) ?? DateTime.now(),
        by: str(j['changedBy']),
        note: str(j['note']),
      );
}

class MaintenanceRequest {
  MaintenanceRequest({
    required this.id,
    this.code = '',
    required this.tenantId,
    required this.unitId,
    required this.propertyId,
    required this.title,
    this.tenantName = '',
    this.unitNo = '',
    this.propertyName = '',
    this.type = MaintenanceType.general,
    this.description = '',
    this.priority = Priority.medium,
    this.attachment = '',
    this.hasAttachment = false,
    this.assignedTo = '',
    this.status = MaintenanceStatus.open,
    List<StatusChange>? history,
    DateTime? createdAt,
  })  : history = history ?? [],
        createdAt = createdAt ?? DateTime.now();

  String id;
  String code;
  String tenantId;
  String unitId;
  String propertyId;
  String tenantName;
  String unitNo;
  String propertyName;
  String title;
  MaintenanceType type;
  String description;
  Priority priority;

  /// Free-text attachment reference.
  String attachment;

  /// True when a photo/file was uploaded for the request.
  bool hasAttachment;
  String assignedTo;
  MaintenanceStatus status;
  List<StatusChange> history;
  DateTime createdAt;

  factory MaintenanceRequest.fromApi(Json j) => MaintenanceRequest(
        id: str(j['id']),
        code: str(j['code']),
        tenantId: str(j['tenantId']),
        unitId: str(j['unitId']),
        propertyId: str(j['propertyId']),
        tenantName: str(j['tenantName']),
        unitNo: str(j['unitNo']),
        propertyName: str(j['propertyName']),
        title: str(j['title']),
        type: apiEnum(MaintenanceType.values, j['type'], MaintenanceType.general),
        description: str(j['description']),
        priority: apiEnum(Priority.values, j['priority'], Priority.medium),
        attachment: str(j['attachment']),
        hasAttachment: j['hasAttachment'] ?? false,
        assignedTo: str(j['assignedTo']),
        status: apiEnum(MaintenanceStatus.values, j['status'], MaintenanceStatus.open),
        history: asJsonList(j['history']).map(StatusChange.fromApi).toList(),
        createdAt: parseDate(j['createdAt']),
      );
}

class AppNotification {
  AppNotification({
    required this.id,
    required this.title,
    required this.body,
    this.read = false,
    DateTime? createdAt,
  }) : createdAt = createdAt ?? DateTime.now();

  String id;
  String title;
  String body;
  bool read;
  DateTime createdAt;

  factory AppNotification.fromApi(Json j) => AppNotification(
        id: str(j['id']),
        title: str(j['title']),
        body: str(j['body']),
        read: j['read'] ?? false,
        createdAt: parseDate(j['createdAt']),
      );
}

class Expense {
  Expense({
    this.id = '',
    required this.date,
    required this.category,
    required this.amount,
    this.propertyId,
    this.description = '',
  });

  String id;
  String? propertyId;
  DateTime date;
  String category;
  double amount;
  String description;

  bool get isNew => id.isEmpty;

  static const categories = [
    'Repair & Maintenance',
    'Utility',
    'Salary',
    'Tax',
    'Cleaning',
    'Security',
    'Other',
  ];

  /// Display name for a [categories] value (the value itself is stored on the server).
  static String categoryLabel(String category) => switch (category) {
        'Repair & Maintenance' => tr('Repair & Maintenance'),
        'Utility' => tr('Utility'),
        'Salary' => tr('Salary'),
        'Tax' => tr('Tax'),
        'Cleaning' => tr('Cleaning'),
        'Security' => tr('Security'),
        'Other' => tr('Other'),
        _ => category,
      };

  factory Expense.fromApi(Json j) => Expense(
        id: str(j['id']),
        propertyId: j['propertyId'],
        date: parseDay(j['expenseDate'])!,
        category: str(j['category']),
        amount: toDouble(j['amount']),
        description: str(j['description']),
      );

  Json toApi() => {
        'propertyId': propertyId,
        'expenseDate': isoDay(date),
        'category': category,
        'amount': amount,
        'description': description,
      };
}

class AuditLog {
  AuditLog({
    required this.id,
    required this.at,
    required this.userName,
    required this.action,
    this.entityType = '',
    this.entityId = '',
    this.details = '',
  });

  String id;
  DateTime at;
  String userName;
  String action;
  String entityType;
  String entityId;
  String details;

  factory AuditLog.fromApi(Json j) => AuditLog(
        id: str(j['id']),
        at: parseDate(j['at']) ?? DateTime.now(),
        userName: str(j['userName']),
        action: str(j['action']),
        entityType: str(j['entityType']),
        entityId: str(j['entityId']),
        details: str(j['details']),
      );
}

/// Organisation settings (`GET/PUT /api/org`).
class AppSettings {
  AppSettings({
    this.orgName = 'My Rentals',
    this.contactName = '',
    this.phone = '',
    this.email = '',
    this.address = '',
    this.currency = '৳',
    this.expiryAlertDays = 30,
    this.autoGenerateRent = true,
  });

  String orgName;
  String contactName;
  String phone;
  String email;
  String address;
  String currency;
  int expiryAlertDays;
  bool autoGenerateRent;

  factory AppSettings.fromApi(Json j) => AppSettings(
        orgName: str(j['name']),
        contactName: str(j['contactName']),
        phone: str(j['phone']),
        email: str(j['email']),
        address: str(j['address']),
        currency: j['currency'] ?? '৳',
        expiryAlertDays: (j['expiryAlertDays'] as num?)?.toInt() ?? 30,
        autoGenerateRent: j['autoGenerateRent'] ?? true,
      );

  Json toApi() => {
        'name': orgName,
        'contactName': contactName,
        'phone': phone,
        'email': email,
        'address': address,
        'currency': currency,
        'expiryAlertDays': expiryAlertDays,
        'autoGenerateRent': autoGenerateRent,
      };
}
