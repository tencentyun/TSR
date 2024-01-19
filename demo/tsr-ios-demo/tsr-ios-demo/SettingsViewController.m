//
//  SettingsViewController.m
//  tsr-ios-demo
//
//

#import <Foundation/Foundation.h>
#import "SettingsViewController.h"
#import "VideoPlayViewController.h"
#import <MobileCoreServices/MobileCoreServices.h>

@interface SettingsViewController () <UIDocumentPickerDelegate, UICollectionViewDataSource, UICollectionViewDelegate>
@property (strong, nonatomic) UISegmentedControl *chooseTypeSegmentedControl;
@property (strong, nonatomic) UILabel *videoLocalHeaderLabel;
@property (strong, nonatomic) UIButton *showCollectionViewButton;
@property (strong, nonatomic) UIButton *chooseFileButton;
@property (strong, nonatomic) UIButton *startPlayButton;
@property (nonatomic, strong) NSURL *selectedVideoURL;
@property (nonatomic, strong) UILabel *videoNameLabel;
@property (nonatomic, strong) UISegmentedControl *resolutionRatioControl;
@property (nonatomic, strong) UIView *horizontalLine;
@property (strong, nonatomic) UICollectionView *collectionView;
@property (strong, nonatomic) NSArray *data;
@property (strong, nonatomic) UIView *overlayView;
@end

@implementation SettingsViewController

- (void)viewDidLoad {
    [super viewDidLoad];
    self.view.backgroundColor = [UIColor whiteColor];
    
    int top = 50;
    int left = 20;
    
    // Choose type segmented control
    self.chooseTypeSegmentedControl = [[UISegmentedControl alloc] initWithItems:@[@"Album's Video", @"Build-in Video"]];
    self.chooseTypeSegmentedControl.tintColor = [UIColor colorWithRed:26/255.0 green:221/255.0 blue:202/255.0 alpha:1.0];
    self.chooseTypeSegmentedControl.frame = CGRectMake(left, top, self.view.bounds.size.width - 40, 50);
    self.chooseTypeSegmentedControl.selectedSegmentIndex = 1;
    [self.chooseTypeSegmentedControl addTarget:self action:@selector(chooseTypeSegmentChanged:) forControlEvents:UIControlEventValueChanged];
    [self.view addSubview:self.chooseTypeSegmentedControl];
    
    // 添加用于显示视频名称的标签
    self.selectedVideoURL = [[NSBundle mainBundle] URLForResource:@"1080P" withExtension:@"mp4"];
    self.videoNameLabel = [[UILabel alloc] initWithFrame:CGRectMake(left, top + 50, self.view.bounds.size.width - 40, 50)];
    self.videoNameLabel.text = @"Using video: 1080P";
    [self.view addSubview:self.videoNameLabel];
    
    // 初始化数据
    self.data = @[@"4K", @"1080P", @"864P", @"720P", @"576P", @"540P"];
    
    // 创建并设置UICollectionViewFlowLayout
    UICollectionViewFlowLayout *layout = [[UICollectionViewFlowLayout alloc] init];
    layout.itemSize = CGSizeMake(1000, 50);
    layout.minimumInteritemSpacing = 10;
    layout.minimumLineSpacing = 10;
    layout.sectionInset = UIEdgeInsetsMake(10, 10, 10, 10);
    layout.scrollDirection = UICollectionViewScrollDirectionVertical;
    
    // 创建并设置UICollectionView
    self.collectionView = [[UICollectionView alloc] initWithFrame:self.view.bounds collectionViewLayout:layout];
    self.collectionView.backgroundColor = [UIColor whiteColor];
    self.collectionView.dataSource = self;
    self.collectionView.hidden = YES; // 初始时隐藏CollectionView
    self.collectionView.delegate = self;
    [self.collectionView registerClass:[UICollectionViewCell class] forCellWithReuseIdentifier:@"CellIdentifier"];
    
    // 创建并设置显示CollectionView的按钮
    self.showCollectionViewButton = [UIButton buttonWithType:UIButtonTypeSystem];
    self.showCollectionViewButton.frame = CGRectMake(left, top + 100, self.view.bounds.size.width - 40, 50);
    [self.showCollectionViewButton setTitle:@"Select build-in video" forState:UIControlStateNormal];
    [self.showCollectionViewButton addTarget:self action:@selector(showCollectionView) forControlEvents:UIControlEventTouchUpInside];
    self.showCollectionViewButton.hidden = NO;
    [self.view addSubview:self.showCollectionViewButton];
    
    // 添加选择文件按钮
    self.chooseFileButton = [UIButton buttonWithType:UIButtonTypeSystem];
    [self.chooseFileButton setTitle:@"Select album's video" forState:UIControlStateNormal];
    [self.chooseFileButton addTarget:self action:@selector(chooseVideoButtonTapped:) forControlEvents:UIControlEventTouchUpInside];
    self.chooseFileButton.frame = CGRectMake(left, top + 100, self.view.bounds.size.width - 40, 50);
    self.chooseFileButton.hidden = YES;
    [self.view addSubview:self.chooseFileButton];
    
    // 分割线
    self.horizontalLine = [[UIView alloc]initWithFrame:CGRectMake(0, top + 150, self.view.bounds.size.width, 1)];
    self.horizontalLine.backgroundColor = [UIColor grayColor];
    [self.view addSubview:self.horizontalLine];

    // 超分辨率标签
    UILabel *srLabel = [[UILabel alloc] initWithFrame:CGRectMake(left, top + 170, self.view.bounds.size.width - 40, 50)];
    srLabel.text = @"Super Resolution Ratio";
    [self.view addSubview:srLabel];
    // 添加超分辨率倍率选择器
    NSArray *resolutionRatios = @[@"OFF", @"1.0", @"1.25", @"1.5", @"1.7", @"2.0"];
    self.resolutionRatioControl = [[UISegmentedControl alloc] initWithItems:resolutionRatios];
    self.resolutionRatioControl.frame = CGRectMake(left, top + 220, self.view.bounds.size.width - 40, 50);
    self.resolutionRatioControl.selectedSegmentIndex = 4; // 默认选择2.0
    [self.view addSubview:self.resolutionRatioControl];
    
    // 分割线
    self.horizontalLine = [[UIView alloc]initWithFrame:CGRectMake(0, top + 300, self.view.bounds.size.width, 1)];
    self.horizontalLine.backgroundColor = [UIColor grayColor];
    [self.view addSubview:self.horizontalLine];

    // 添加播放视频按钮
    self.startPlayButton = [UIButton buttonWithType:UIButtonTypeSystem];
    [self.startPlayButton setTitle:@"Play Video" forState:UIControlStateNormal];
    [self.startPlayButton addTarget:self action:@selector(playVideoButtonTapped:) forControlEvents:UIControlEventTouchUpInside];
    self.startPlayButton.frame = CGRectMake([UIScreen mainScreen].bounds.size.width / 2 - 50, top + 320, 100, 50);
    [self.view addSubview:self.startPlayButton];
    
    // 创建并设置覆盖其他控件的视图
    self.overlayView = [[UIView alloc] initWithFrame:self.view.bounds];
    self.overlayView.backgroundColor = [[UIColor grayColor] colorWithAlphaComponent:0.5]; // 半透明的灰色
    self.overlayView.hidden = YES; // 初始时隐藏
    [self.view addSubview:self.overlayView];
    [self.view insertSubview:self.collectionView aboveSubview:self.self.overlayView];
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
    // 获取选中的超分辨率倍率
    NSString *selectedResolutionRatio = [self.resolutionRatioControl titleForSegmentAtIndex:self.resolutionRatioControl.selectedSegmentIndex];
    float srRatio = [selectedResolutionRatio isEqualToString:@"OFF"] ? -1 : [selectedResolutionRatio floatValue];
    NSLog(@"Selected resolution ratio: %@", selectedResolutionRatio);
    
    // 显示 ViewController
    VideoPlayViewController *videoPlayVC = [[VideoPlayViewController alloc] initWithVideoURL:self.selectedVideoURL srRatio:srRatio];
    videoPlayVC.modalPresentationStyle = UIModalPresentationFullScreen;
    [self presentViewController:videoPlayVC animated:YES completion:nil];
}

- (void)chooseTypeSegmentChanged:(UISegmentedControl *)sender {
    switch (sender.selectedSegmentIndex) {
        case 0: // Album's video
            // 显示与Album's video相关的UI
            self.showCollectionViewButton.hidden = YES;
            self.chooseFileButton.hidden = NO;
            break;
        case 1: // Build-in Video
            // 显示与Build-in Video相关的UI
            self.showCollectionViewButton.hidden = NO;
            self.chooseFileButton.hidden = YES;
            break;
        default:
            break;
    }
}

- (void)showCollectionView {
    self.collectionView.hidden = NO; // 显示CollectionView
    self.overlayView.hidden = NO;
}

#pragma mark - UIImagePickerControllerDelegate

- (void)imagePickerController:(UIImagePickerController *)picker didFinishPickingMediaWithInfo:(NSDictionary<UIImagePickerControllerInfoKey,id> *)info {
    self.selectedVideoURL = info[UIImagePickerControllerMediaURL];
    self.videoNameLabel.text = [NSString stringWithFormat:@"%@%@", @"Using video: ", [self.selectedVideoURL lastPathComponent]];
    // 在这里处理选定的视频（例如，读取内容、显示预览等）
    NSLog(@"Selected album's video: %@", self.selectedVideoURL);

    // 关闭 UIImagePickerController
    [picker dismissViewControllerAnimated:YES completion:nil];
}

- (void)imagePickerControllerDidCancel:(UIImagePickerController *)picker {
    // 用户取消了视频选择
    NSLog(@"Video selection was cancelled");
    [picker dismissViewControllerAnimated:YES completion:nil];
}


#pragma mark - UICollectionViewDataSource

- (NSInteger)collectionView:(UICollectionView *)collectionView numberOfItemsInSection:(NSInteger)section {
    return self.data.count;
}

- (UICollectionViewCell *)collectionView:(UICollectionView *)collectionView cellForItemAtIndexPath:(NSIndexPath *)indexPath {
    UICollectionViewCell *cell = [collectionView dequeueReusableCellWithReuseIdentifier:@"CellIdentifier" forIndexPath:indexPath];
    
    UILabel *label = (UILabel *)[cell viewWithTag:100];
    if (!label) {
        label = [[UILabel alloc] initWithFrame:cell.bounds];
        label.tag = 100;
        label.textAlignment = NSTextAlignmentCenter;
        [cell addSubview:label];
    }
    label.text = self.data[indexPath.item];
    cell.backgroundColor = [UIColor lightGrayColor];
    
    return cell;
}

#pragma mark - UICollectionViewDelegate

- (void)collectionView:(UICollectionView *)collectionView didSelectItemAtIndexPath:(NSIndexPath *)indexPath {
    NSString *selectedItem = self.data[indexPath.item];
    self.videoNameLabel.text = [NSString stringWithFormat:@"%@%@", @"Using video: ", selectedItem];
    
    self.overlayView.hidden = YES;
    self.collectionView.hidden = YES; // 初始时隐藏CollectionView
    
    self.selectedVideoURL = [[NSBundle mainBundle] URLForResource:selectedItem withExtension:@"mp4"];
    NSLog(@"Selected item: %@", selectedItem);
}

@end
