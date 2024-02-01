//
//  SettingsViewController.m
//  tsr-ios-demo
//
//

#import <Foundation/Foundation.h>
#import "SettingsViewController.h"
#import "VideoPlayViewController.h"
#import <MobileCoreServices/MobileCoreServices.h>

@interface SettingsViewController () <UIDocumentPickerDelegate>

@property (nonatomic, strong) NSURL *selectedVideoURL;
@property (nonatomic, strong) UILabel *videoNameLabel;
@property (nonatomic, strong) UISegmentedControl *resolutionRatioControl;

@end

@implementation SettingsViewController

- (void)viewDidLoad {
    [super viewDidLoad];
    self.view.backgroundColor = [UIColor whiteColor];
    
    // 添加用于显示视频名称的标签
    self.videoNameLabel = [[UILabel alloc] initWithFrame:CGRectMake(20, 50, self.view.bounds.size.width - 40, 50)];
    self.videoNameLabel.text = @"Using video: Default video";
    [self.view addSubview:self.videoNameLabel];
    
    // 添加选择文件按钮
    UIButton *chooseFileButton = [UIButton buttonWithType:UIButtonTypeSystem];
    [chooseFileButton setTitle:@"Choose Video" forState:UIControlStateNormal];
    [chooseFileButton addTarget:self action:@selector(chooseVideoButtonTapped:) forControlEvents:UIControlEventTouchUpInside];
    chooseFileButton.frame = CGRectMake(20, 100, 100, 50);
    [self.view addSubview:chooseFileButton];
    
    // 分割线
    UIView *horizontalLine = [[UIView alloc]initWithFrame:CGRectMake(0, 150, self.view.bounds.size.width, 1)];
    horizontalLine.backgroundColor = [UIColor grayColor];
    [self.view addSubview:horizontalLine];

    // 超分辨率标签
    UILabel *srLabel = [[UILabel alloc] initWithFrame:CGRectMake(20, 170, self.view.bounds.size.width - 40, 50)];
    srLabel.text = @"Super Resolution Ratio";
    [self.view addSubview:srLabel];
    // 添加超分辨率倍率选择器
    NSArray *resolutionRatios = @[@"1.0", @"1.3", @"1.5", @"1.7", @"2.0"];
    self.resolutionRatioControl = [[UISegmentedControl alloc] initWithItems:resolutionRatios];
    self.resolutionRatioControl.frame = CGRectMake(20, 220, self.view.bounds.size.width - 40, 50);
    self.resolutionRatioControl.selectedSegmentIndex = 4; // 默认选择2.0
    [self.view addSubview:self.resolutionRatioControl];
    
    // 分割线
    horizontalLine = [[UIView alloc]initWithFrame:CGRectMake(0, 300, self.view.bounds.size.width, 1)];
    horizontalLine.backgroundColor = [UIColor grayColor];
    [self.view addSubview:horizontalLine];

    // 添加播放视频按钮
    UIButton *playVideoButton = [UIButton buttonWithType:UIButtonTypeSystem];
    [playVideoButton setTitle:@"Play Video" forState:UIControlStateNormal];
    [playVideoButton addTarget:self action:@selector(playVideoButtonTapped:) forControlEvents:UIControlEventTouchUpInside];
    playVideoButton.frame = CGRectMake([UIScreen mainScreen].bounds.size.width / 2 - 50, 320, 100, 50);
    [self.view addSubview:playVideoButton];
}

- (IBAction)chooseVideoButtonTapped:(id)sender {
    // 创建并显示 UIImagePickerController
    UIImagePickerController *imagePickerController = [[UIImagePickerController alloc] init];
    imagePickerController.sourceType = UIImagePickerControllerSourceTypePhotoLibrary;
    imagePickerController.mediaTypes = @[(NSString *)kUTTypeMovie];
    imagePickerController.delegate = self;
    [self presentViewController:imagePickerController animated:YES completion:nil];
}


- (void)playVideoButtonTapped:(id)sender {
    // 使用选定的视频URL或默认视频URL
    NSURL *videoURL = self.selectedVideoURL ?: [[NSBundle mainBundle] URLForResource:@"girl-544x960" withExtension:@"mp4"];
    // 获取选中的超分辨率倍率
    NSString *selectedResolutionRatio = [self.resolutionRatioControl titleForSegmentAtIndex:self.resolutionRatioControl.selectedSegmentIndex];
    NSLog(@"Selected resolution ratio: %@", selectedResolutionRatio);

    // 显示 ViewController
    VideoPlayViewController *videoPlayVC = [[VideoPlayViewController alloc] initWithVideoURL:videoURL srRatio:[selectedResolutionRatio floatValue]];
    videoPlayVC.modalPresentationStyle = UIModalPresentationFullScreen;
    [self presentViewController:videoPlayVC animated:YES completion:nil];
}

#pragma mark - UIImagePickerControllerDelegate

- (void)imagePickerController:(UIImagePickerController *)picker didFinishPickingMediaWithInfo:(NSDictionary<UIImagePickerControllerInfoKey,id> *)info {
    self.selectedVideoURL = info[UIImagePickerControllerMediaURL];
    self.videoNameLabel.text = [NSString stringWithFormat:@"%@%@", @"Using video: ", [self.selectedVideoURL lastPathComponent]];
    // 在这里处理选定的视频（例如，读取内容、显示预览等）
    NSLog(@"Selected video: %@", self.selectedVideoURL);

    // 关闭 UIImagePickerController
    [picker dismissViewControllerAnimated:YES completion:nil];
}

- (void)imagePickerControllerDidCancel:(UIImagePickerController *)picker {
    // 用户取消了视频选择
    NSLog(@"Video selection was cancelled");
    [picker dismissViewControllerAnimated:YES completion:nil];
}

@end
