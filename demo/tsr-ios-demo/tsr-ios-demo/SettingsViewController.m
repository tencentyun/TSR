#import "SettingsViewController.h"
#import "VideoPlayViewController.h"
#import <MobileCoreServices/MobileCoreServices.h>

@interface SettingsViewController () <UIDocumentPickerDelegate, UICollectionViewDataSource, UICollectionViewDelegate, UIPickerViewDataSource, UIPickerViewDelegate>
@property (strong, nonatomic) UISegmentedControl *chooseTypeSegmentedControl;
@property (strong, nonatomic) UILabel *videoLocalHeaderLabel;
@property (strong, nonatomic) UILabel *srLabel;
@property (strong, nonatomic) UILabel *selectAlgorithmLabel;
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
@property (strong, nonatomic) UIPickerView *algorithmPickerView; // NEW
@property (strong, nonatomic) NSArray *algorithmOptions; // NEW
@end

@implementation SettingsViewController

- (void)viewDidLoad {
    [super viewDidLoad];
    self.view.backgroundColor = [UIColor whiteColor];

    int top = 50;
    int left = 20;

    // Choose type segmented control
    self.chooseTypeSegmentedControl = [[UISegmentedControl alloc] initWithItems:@[@"Play Local Video", @"Play Built-in Video"]];
    self.chooseTypeSegmentedControl.tintColor = [UIColor colorWithRed:26/255.0 green:221/255.0 blue:202/255.0 alpha:1.0];
    self.chooseTypeSegmentedControl.frame = CGRectMake(left, top, self.view.bounds.size.width - 40, 50);
    self.chooseTypeSegmentedControl.selectedSegmentIndex = 1;
    [self.chooseTypeSegmentedControl addTarget:self action:@selector(chooseTypeSegmentChanged:) forControlEvents:UIControlEventValueChanged];
    [self.view addSubview:self.chooseTypeSegmentedControl];

    // Add label to display video name
    top += 50;
    self.selectedVideoURL = [[NSBundle mainBundle] URLForResource:@"girl-544x960" withExtension:@"mp4"];
    self.videoNameLabel = [[UILabel alloc] initWithFrame:CGRectMake(left, top, self.view.bounds.size.width - 40, 50)];
    self.videoNameLabel.text = @"Selected Video: girl-544x960";
    [self.view addSubview:self.videoNameLabel];

    // Initialize data
    self.data = @[@"4K", @"1080P", @"864P", @"720P", @"576P", @"540P", @"girl-544x960"];

    // Create and set UICollectionViewFlowLayout
    UICollectionViewFlowLayout *layout = [[UICollectionViewFlowLayout alloc] init];
    layout.itemSize = CGSizeMake(1000, 50);
    layout.minimumInteritemSpacing = 10;
    layout.minimumLineSpacing = 10;
    layout.sectionInset = UIEdgeInsetsMake(10, 10, 10, 10);
    layout.scrollDirection = UICollectionViewScrollDirectionVertical;

    // Create and set UICollectionView
    self.collectionView = [[UICollectionView alloc] initWithFrame:self.view.bounds collectionViewLayout:layout];
    self.collectionView.backgroundColor = [UIColor whiteColor];
    self.collectionView.dataSource = self;
    self.collectionView.hidden = YES; // Initially hide CollectionView
    self.collectionView.delegate = self;
    [self.collectionView registerClass:[UICollectionViewCell class] forCellWithReuseIdentifier:@"CellIdentifier"];

    // Create and set button to show CollectionView
    top += 50;
    self.showCollectionViewButton = [UIButton buttonWithType:UIButtonTypeSystem];
    self.showCollectionViewButton.frame = CGRectMake(left, top, self.view.bounds.size.width - 40, 50);
    [self.showCollectionViewButton setTitle:@"Play Built-in Video" forState:UIControlStateNormal];
    [self.showCollectionViewButton addTarget:self action:@selector(showCollectionView) forControlEvents:UIControlEventTouchUpInside];
    self.showCollectionViewButton.hidden = NO;
    [self.view addSubview:self.showCollectionViewButton];

    // Add button to choose file
    self.chooseFileButton = [UIButton buttonWithType:UIButtonTypeSystem];
    [self.chooseFileButton setTitle:@"Play Local Video" forState:UIControlStateNormal];
    [self.chooseFileButton addTarget:self action:@selector(chooseVideoButtonTapped:) forControlEvents:UIControlEventTouchUpInside];
    self.chooseFileButton.frame = CGRectMake(left, top, self.view.bounds.size.width - 40, 50);
    self.chooseFileButton.hidden = YES;
    [self.view addSubview:self.chooseFileButton];

    // Separator line
    top += 50;
    self.horizontalLine = [[UIView alloc]initWithFrame:CGRectMake(0, top, self.view.bounds.size.width, 1)];
    self.horizontalLine.backgroundColor = [UIColor grayColor];
    [self.view addSubview:self.horizontalLine];

    // Video processing algorithm label
    _selectAlgorithmLabel = [[UILabel alloc] initWithFrame:CGRectMake(left, top, self.view.bounds.size.width - 40, 50)];
    _selectAlgorithmLabel.text = @"Video Processing Algorithm";
    [self.view addSubview:_selectAlgorithmLabel];

    // Initialize algorithmOptions data source array
    self.algorithmOptions = @[@"Normal", @"Super-Resolution(STD)", @"Super-Resolution(PRO-Fast)", @"Super-Resolution(PRO-High Quality)", @"Enhanced(PRO-Fast)", @"Enhanced(PRO-High Quality)"];
    // Create UIPickerView
    top += 50;
    self.algorithmPickerView = [[UIPickerView alloc] initWithFrame:CGRectMake(left, top, self.view.bounds.size.width - 40, 100)];
    self.algorithmPickerView.dataSource = self;
    self.algorithmPickerView.delegate = self;
    [self.view addSubview:self.algorithmPickerView];

    top += 120;
    self.horizontalLine = [[UIView alloc]initWithFrame:CGRectMake(0, top, self.view.bounds.size.width, 1)];
    self.horizontalLine.backgroundColor = [UIColor grayColor];
    [self.view addSubview:self.horizontalLine];

    // Magnification label
    _srLabel = [[UILabel alloc] initWithFrame:CGRectMake(left, top, self.view.bounds.size.width - 40, 50)];
    _srLabel.text = @"Magnification";
    [self.view addSubview:_srLabel];
    // Add magnification ratio selector
    NSArray *resolutionRatios = @[@"1.0", @"1.25", @"1.5", @"1.7", @"2.0", @"auto"];
    top += 50;
    self.resolutionRatioControl = [[UISegmentedControl alloc] initWithItems:resolutionRatios]; self.resolutionRatioControl.frame = CGRectMake(left, top, self.view.bounds.size.width - 40, 50); self.resolutionRatioControl.selectedSegmentIndex = 4; // Default to 2.0
    [self.view addSubview:self.resolutionRatioControl];
    // Separator line
    top += 70;
    self.horizontalLine = [[UIView alloc]initWithFrame:CGRectMake(0, top, self.view.bounds.size.width, 1)];
    self.horizontalLine.backgroundColor = [UIColor grayColor];
    [self.view addSubview:self.horizontalLine];

    // Add play video button
    top += 100;
    self.startPlayButton = [UIButton buttonWithType:UIButtonTypeSystem];
    [self.startPlayButton setTitle:@"Play Video" forState:UIControlStateNormal];
    [self.startPlayButton addTarget:self action:@selector(playVideoButtonTapped:) forControlEvents:UIControlEventTouchUpInside];
    self.startPlayButton.frame = CGRectMake([UIScreen mainScreen].bounds.size.width / 2 - 50, top, 100, 50);
    [self.view addSubview:self.startPlayButton];

    // Create and set overlay view
    self.overlayView = [[UIView alloc] initWithFrame:self.view.bounds];
    self.overlayView.backgroundColor = [[UIColor grayColor] colorWithAlphaComponent:0.5]; // Semi-transparent gray
    self.overlayView.hidden = YES; // Initially hide
    [self.view addSubview:self.overlayView];
    [self.view insertSubview:self.collectionView aboveSubview:self.self.overlayView];
}

- (IBAction)chooseVideoButtonTapped:(id)sender {
    // Create and display UIImagePickerController
    UIImagePickerController *imagePickerController = [[UIImagePickerController alloc] init];
    imagePickerController.sourceType = UIImagePickerControllerSourceTypePhotoLibrary;
    imagePickerController.mediaTypes = @[(NSString *)kUTTypeMovie];
    imagePickerController.delegate = self;
    [self presentViewController:imagePickerController animated:YES completion:nil];
}

- (void)playVideoButtonTapped:(id)sender {
    // Get selected algorithm
    NSInteger selectedAlgorithmIndex = [self.algorithmPickerView selectedRowInComponent:0];
    NSString *selectedAlgorithm = self.algorithmOptions[selectedAlgorithmIndex];
    NSLog(@"Selected algorithm: %@", selectedAlgorithm);

    // Get selected magnification ratio
    NSString *selectedResolutionRatio = [self.resolutionRatioControl titleForSegmentAtIndex:self.resolutionRatioControl.selectedSegmentIndex];
    float srRatio;
    if ([@"auto" isEqualToString:selectedResolutionRatio]) {
        srRatio = -1;
    } else {
        srRatio = [selectedResolutionRatio floatValue];
    }
    NSLog(@"Selected resolution ratio: %@", selectedResolutionRatio);

    // Display ViewController
    VideoPlayViewController *videoPlayVC = [[VideoPlayViewController alloc] initWithVideoURL:self.selectedVideoURL srRatio:srRatio algorithm:selectedAlgorithm];
    videoPlayVC.modalPresentationStyle = UIModalPresentationFullScreen;
    [self presentViewController:videoPlayVC animated:YES completion:nil];
}

- (void)chooseTypeSegmentChanged:(UISegmentedControl *)sender {
    switch (sender.selectedSegmentIndex) {
        case 0: // Album's video
            // Display UI related to Album's video
            self.showCollectionViewButton.hidden = YES;
            self.chooseFileButton.hidden = NO;
            break;
        case 1: // Built-in Video
            // Display UI related to Built-in Video
            self.showCollectionViewButton.hidden = NO;
            self.chooseFileButton.hidden = YES;
            break;
        default:
            break;
    }
}

- (void)showCollectionView {
    self.collectionView.hidden = NO; // Show CollectionView
    self.overlayView.hidden = NO;
}

#pragma mark - UIImagePickerControllerDelegate

- (void)imagePickerController:(UIImagePickerController *)picker didFinishPickingMediaWithInfo:(NSDictionary<UIImagePickerControllerInfoKey,id> *)info {
    self.selectedVideoURL = info[UIImagePickerControllerMediaURL];
    self.videoNameLabel.text = [NSString stringWithFormat:@"%@%@", @"Using video: ", [self.selectedVideoURL lastPathComponent]];
    // Handle the selected video here (e.g., read content, display preview, etc.)
    NSLog(@"Selected album's video: %@", self.selectedVideoURL);

    // Dismiss UIImagePickerController
    [picker dismissViewControllerAnimated:YES completion:nil];
}

- (void)imagePickerControllerDidCancel:(UIImagePickerController *)picker {
    // User cancelled video selection
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
    self.collectionView.hidden = YES; // Initially hide the CollectionView

    self.selectedVideoURL = [[NSBundle mainBundle] URLForResource:selectedItem withExtension:@"mp4"];
    NSLog(@"Selected item: %@", selectedItem);
}

#pragma mark - UIPickerViewDataSource

- (NSInteger)numberOfComponentsInPickerView:(UIPickerView *)pickerView {
    return 1;
}

- (NSInteger)pickerView:(UIPickerView *)pickerView numberOfRowsInComponent:(NSInteger)component {
    return self.algorithmOptions.count;
}

#pragma mark - UIPickerViewDelegate

- (NSString *)pickerView:(UIPickerView *)pickerView titleForRow:(NSInteger)row forComponent:(NSInteger)component {
    return self.algorithmOptions[row];
}

@end
