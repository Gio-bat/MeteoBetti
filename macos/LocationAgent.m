#import <CoreLocation/CoreLocation.h>
#import <Foundation/Foundation.h>

@interface LocationAgent : NSObject <CLLocationManagerDelegate>
@property(nonatomic, strong) CLLocationManager *manager;
@property(nonatomic, strong) CLGeocoder *geocoder;
@property(nonatomic, assign) BOOL requestedLocation;
@property(nonatomic, assign) BOOL finished;
@property(nonatomic, copy) NSString *output;
@property(nonatomic, copy) NSString *failure;
- (void)start;
@end

@implementation LocationAgent

- (instancetype)init {
    self = [super init];
    if (self) {
        _manager = [[CLLocationManager alloc] init];
        _manager.delegate = self;
        _manager.desiredAccuracy = kCLLocationAccuracyBest;
        _geocoder = [[CLGeocoder alloc] init];
    }
    return self;
}

- (void)start {
    [self handleAuthorization:self.manager.authorizationStatus];
}

- (void)locationManagerDidChangeAuthorization:(CLLocationManager *)manager {
    [self handleAuthorization:manager.authorizationStatus];
}

- (void)handleAuthorization:(CLAuthorizationStatus)status {
    switch (status) {
        case kCLAuthorizationStatusNotDetermined:
            [self.manager requestWhenInUseAuthorization];
            break;
        case kCLAuthorizationStatusAuthorizedAlways:
            if (!self.requestedLocation) {
                self.requestedLocation = YES;
                [self.manager requestLocation];
            }
            break;
        case kCLAuthorizationStatusDenied:
        case kCLAuthorizationStatusRestricted:
            [self finishWithError:@"Permesso di localizzazione negato nelle impostazioni di macOS"];
            break;
        default:
            [self finishWithError:@"Stato del permesso di localizzazione non riconosciuto"];
            break;
    }
}

- (void)locationManager:(CLLocationManager *)manager didUpdateLocations:(NSArray<CLLocation *> *)locations {
    CLLocation *location = locations.lastObject;
    if (location == nil || location.horizontalAccuracy < 0) {
        [self finishWithError:@"macOS non ha fornito coordinate valide"];
        return;
    }

    __weak LocationAgent *weakSelf = self;
    [self.geocoder reverseGeocodeLocation:location
                        completionHandler:^(NSArray<CLPlacemark *> *placemarks, NSError *error) {
        LocationAgent *strongSelf = weakSelf;
        if (strongSelf == nil) return;
        CLPlacemark *placemark = placemarks.firstObject;
        NSString *city = placemark.locality ?: placemark.subAdministrativeArea ?: @"";
        NSString *country = placemark.country ?: @"";
        strongSelf.output = [NSString stringWithFormat:@"%.8f|%.8f|%@|%@",
                             location.coordinate.latitude,
                             location.coordinate.longitude,
                             city,
                             country];
        [strongSelf finish];
    }];
}

- (void)locationManager:(CLLocationManager *)manager didFailWithError:(NSError *)error {
    [self finishWithError:error.localizedDescription];
}

- (void)finishWithError:(NSString *)message {
    self.failure = message;
    [self finish];
}

- (void)finish {
    self.finished = YES;
    CFRunLoopStop(CFRunLoopGetMain());
}

@end

int main(void) {
    @autoreleasepool {
        LocationAgent *agent = [[LocationAgent alloc] init];
        [agent start];

        NSDate *deadline = [NSDate dateWithTimeIntervalSinceNow:30];
        while (!agent.finished && deadline.timeIntervalSinceNow > 0) {
            [[NSRunLoop mainRunLoop] runMode:NSDefaultRunLoopMode beforeDate:deadline];
        }

        if (agent.output.length == 0) {
            NSString *message = agent.failure ?: @"Tempo scaduto durante la richiesta di posizione";
            fprintf(stderr, "%s\n", message.UTF8String);
            return EXIT_FAILURE;
        }

        printf("%s\n", agent.output.UTF8String);
        return EXIT_SUCCESS;
    }
}
