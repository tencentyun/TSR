//
//  tsr_ios_demoUITestsLaunchTests.m
//  tsr-ios-demoUITests
//
//  Created by 高峻峰 on 2024/1/24.
//

#import <XCTest/XCTest.h>

@interface tsr_ios_demoUITestsLaunchTests : XCTestCase

@end

@implementation tsr_ios_demoUITestsLaunchTests

+ (BOOL)runsForEachTargetApplicationUIConfiguration {
    return YES;
}

- (void)setUp {
    self.continueAfterFailure = NO;
}

- (void)testLaunch {
    XCUIApplication *app = [[XCUIApplication alloc] init];
    [app launch];

    // Insert steps here to perform after app launch but before taking a screenshot,
    // such as logging into a test account or navigating somewhere in the app

    XCTAttachment *attachment = [XCTAttachment attachmentWithScreenshot:XCUIScreen.mainScreen.screenshot];
    attachment.name = @"Launch Screen";
    attachment.lifetime = XCTAttachmentLifetimeKeepAlways;
    [self addAttachment:attachment];
}

@end
