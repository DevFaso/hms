//package com.example.hms.service;
//
//import com.amazonaws.services.s3.AmazonS3;
//import com.amazonaws.services.s3.model.CannedAccessControlList;
//import com.amazonaws.services.s3.model.DeleteObjectRequest;
//import com.amazonaws.services.s3.model.PutObjectRequest;
//import org.slf4j.Logger;
//import org.slf4j.LoggerFactory;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.beans.factory.annotation.Value;
//import org.springframework.stereotype.Service;
//import org.springframework.web.multipart.MultipartFile;
//
//import java.io.File;
//import java.io.FileOutputStream;
//import java.io.IOException;
//import java.util.Date;
//import java.util.Objects;
//
//@Service
//public class S3StorageService {
//
//    private static final Logger logger = LoggerFactory.getLogger(S3StorageService.class);
//
//    private final AmazonS3 s3client;
//
//    @Value("${aws.s3.bucketName}")
//    private String bucketName;
//
//    @Autowired
//    public S3StorageService(AmazonS3 s3client) {
//        this.s3client = s3client;
//    }
//
//    private File convertMultiPartToFile(MultipartFile file) throws IOException {
//        File convFile = new File(Objects.requireNonNull(file.getOriginalFilename()));
//        try (FileOutputStream fos = new FileOutputStream(convFile)) {
//            fos.write(file.getBytes());
//        }
//        return convFile;
//    }
//
//    private String generateFileName(MultipartFile multiPart) {
//        return new Date().getTime() + "-" + multiPart.getOriginalFilename().replace(" ", "_");
//    }
//
//    private void uploadFileTos3bucket(String fileName, File file) {
//        s3client.putObject(new PutObjectRequest(bucketName, fileName, file)
//                .withCannedAcl(CannedAccessControlList.PublicRead)); // Or Private, depending on your needs
//    }
//
//    public String uploadFile(MultipartFile multipartFile) {
//        String fileUrl = "";
//        try {
//            File file = convertMultiPartToFile(multipartFile);
//            String fileName = generateFileName(multipartFile);
//            fileUrl = s3client.getUrl(bucketName, fileName).toString();
//            uploadFileTos3bucket(fileName, file);
//            if (!file.delete()) {
//                logger.warn("Could not delete temporary file: {}", file.getAbsolutePath());
//            }
//        } catch (Exception e) {
//            logger.error("Error uploading file to S3: ", e);
//        }
//        return fileUrl;
//    }
//
//    public String deleteFileFromS3Bucket(String fileUrl) {
//        try {
//            String fileName = fileUrl.substring(fileUrl.lastIndexOf("/") + 1);
//            s3client.deleteObject(new DeleteObjectRequest(bucketName, fileName));
//            return "Successfully deleted";
//        } catch (Exception e) {
//            logger.error("Error deleting file from S3: ", e);
//            return "Error deleting file";
//        }
//    }
//}
//
