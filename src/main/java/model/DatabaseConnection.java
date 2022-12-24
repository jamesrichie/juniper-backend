package model;

import java.io.*;
import java.sql.*;
import java.util.*;
import java.security.*;
import java.security.spec.*;

import javax.crypto.*;
import javax.crypto.spec.*;
import org.springframework.http.*;
import org.springframework.util.ResourceUtils;

import exceptions.*;
import static model.Statements.*;

public class DatabaseConnection {

    // Database connection
    private final Connection conn;

    // Flag enabling the use of testing features
    private final Boolean testEnabled;

    // Flag enabling the creation of savepoints
    private Boolean testSavepointEnabled;

    // Password hashing parameter constants
    private static final int HASH_STRENGTH = 65536;
    private static final int KEY_LENGTH = 128;

    // Number of attempts when encountering deadlock
    private static final int MAX_ATTEMPTS = 16;

    // Create statements
    public PreparedStatement createCourseStatement;
    public PreparedStatement createMediaStatement;
    public PreparedStatement createRegistrationStatement;
    public PreparedStatement createRelationshipStatement;
    public PreparedStatement createUserStatement;

    // Delete statements
    public PreparedStatement deleteMediaStatement;
    public PreparedStatement deleteRegistrationStatement;

    // Update statements
    public PreparedStatement updateBiographyStatement;
    public PreparedStatement updateCardColorStatement;
    public PreparedStatement updateCredentialsStatement;
    public PreparedStatement updateEducationInformationStatement;
    public PreparedStatement updateEmailVerificationStatement;
    public PreparedStatement updatePersonalInformationStatement;
    public PreparedStatement updateProfilePictureStatement;
    public PreparedStatement updateRefreshTokenStatement;
    public PreparedStatement updateRelationshipStatement;

    // Select statements
    public PreparedStatement resolveCourseIdUniversityIdToCourseRecordStatement;
    public PreparedStatement resolveEmailToUserRecordStatement;
    public PreparedStatement resolveUserHandleToUserRecordStatement;
    public PreparedStatement resolveUserIdFriendUserIdToRecordStatement;
    public PreparedStatement resolveUserIdToUserRecordStatement;

    // Boolean statements
    public PreparedStatement checkEmailVerificationStatement;
    public PreparedStatement checkVerificationCodeActiveStatement;
    public PreparedStatement checkVerificationCodeUsedStatement;

    /**
     * Creates a connection to the database specified in dbconn.credentials
     */
    public DatabaseConnection() throws IOException, SQLException {
        this(false);
    }

    /**
     * Creates a connection to the database specified in dbconn.credentials
     *
     * @param testEnabled flag enabling the use of testing features
     */
    public DatabaseConnection(Boolean testEnabled) throws IOException, SQLException {
        this.testEnabled = testEnabled;
        conn = openConnectionFromDbConn();
        prepareStatements();
    }

    /**
     * Return the connection specified by the dbconn.credentials file
     */
    private static Connection openConnectionFromDbConn() throws IOException, SQLException {
        Properties configProps = new Properties();
        configProps.load(new FileInputStream(ResourceUtils.getFile("classpath:credentials/dbconn.credentials")));

        String endpoint = configProps.getProperty("RDS_ENDPOINT");
        String port = configProps.getProperty("RDS_PORT");
        String dbName = configProps.getProperty("RDS_DB_NAME");
        String adminName = configProps.getProperty("RDS_USERNAME");
        String password = configProps.getProperty("RDS_PASSWORD");

        String connectionUrl = String.format("jdbc:mysql://%s:%s/%s?user=%s&password=%s",
                                             endpoint, port, dbName, adminName, password);
        Connection conn = DriverManager.getConnection(connectionUrl);

        // Automatically commit after each statement
        conn.setAutoCommit(true);

        // Set the transaction isolation level to serializable
        conn.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);

        return conn;
    }

    /**
     * Gets the underlying connection
     */
    public Connection getConnection() {
        return conn;
    }

    /**
     * Closes the application-to-database connection
     */
    public void closeConnection() throws SQLException {
        conn.close();
    }

    /*
     * Prepare all the SQL statements
     */
    private void prepareStatements() throws SQLException {
        // Create statements
        createCourseStatement = conn.prepareStatement(CREATE_COURSE);
        createMediaStatement = conn.prepareStatement(CREATE_MEDIA);
        createRegistrationStatement = conn.prepareStatement(CREATE_REGISTRATION);
        createRelationshipStatement = conn.prepareStatement(CREATE_RELATIONSHIP);
        createUserStatement = conn.prepareStatement(CREATE_USER);

        // Delete statements
        deleteMediaStatement = conn.prepareStatement(DELETE_MEDIA);
        deleteRegistrationStatement = conn.prepareStatement(DELETE_REGISTRATION);

        // Update statements
        updateBiographyStatement = conn.prepareStatement(UPDATE_BIOGRAPHY);
        updateCardColorStatement = conn.prepareStatement(UPDATE_CARD_COLOR);
        updateCredentialsStatement = conn.prepareStatement(UPDATE_CREDENTIALS);
        updateEducationInformationStatement = conn.prepareStatement(UPDATE_EDUCATION_INFORMATION);
        updateEmailVerificationStatement = conn.prepareStatement(UPDATE_EMAIL_VERIFICATION);
        updatePersonalInformationStatement = conn.prepareStatement(UPDATE_PERSONAL_INFORMATION);
        updateProfilePictureStatement = conn.prepareStatement(UPDATE_PROFILE_PICTURE);
        updateRefreshTokenStatement = conn.prepareStatement(UPDATE_REFRESH_TOKEN);
        updateRelationshipStatement = conn.prepareStatement(UPDATE_RELATIONSHIP);

        // Select statements
        resolveCourseIdUniversityIdToCourseRecordStatement = conn.prepareStatement(RESOLVE_COURSE_ID_UNIVERSITY_ID_TO_COURSE_RECORD);
        resolveEmailToUserRecordStatement = conn.prepareStatement(RESOLVE_EMAIL_TO_USER_RECORD);
        resolveUserHandleToUserRecordStatement = conn.prepareStatement(RESOLVE_USER_HANDLE_TO_USER_RECORD);
        resolveUserIdFriendUserIdToRecordStatement = conn.prepareStatement(RESOLVE_USER_ID_FRIEND_USER_ID_TO_RECORD);
        resolveUserIdToUserRecordStatement = conn.prepareStatement(RESOLVE_USER_ID_TO_USER_RECORD);

        // Boolean statements
        checkEmailVerificationStatement = conn.prepareStatement(CHECK_EMAIL_VERIFICATION);
        checkVerificationCodeActiveStatement = conn.prepareStatement(CHECK_VERIFICATION_CODE_ACTIVE);
        checkVerificationCodeUsedStatement = conn.prepareStatement(CHECK_VERIFICATION_CODE_USED);
    }

    /**
     * Creates a new user with an unverified email
     *
     * @effect tbl_users (RW), acquires lock
     * @return true / 200 status code iff successfully created new user
     */
    public ResponseEntity<Boolean> transaction_createUser(String userId, String userHandle, String name, String email,
                                                         String password, String verificationCode) {

        for (int attempts = 0; attempts < MAX_ATTEMPTS; attempts++) {
            try {
                beginTransaction();

                // checks that email is not mapped to a user record
                ResultSet resolveEmailToUserRecordRS = executeQuery(resolveEmailToUserRecordStatement, email);
                if (resolveEmailToUserRecordRS.next()) {
                    resolveEmailToUserRecordRS.close();
                    return new ResponseEntity<>(false, HttpStatus.BAD_REQUEST);
                }
                resolveEmailToUserRecordRS.close();

                // checks that user handle is not mapped to a user id
                if (transaction_userHandleToUserId(userHandle).getBody() != null) {
                    return new ResponseEntity<>(false, HttpStatus.BAD_REQUEST);
                }

                byte[] salt = get_salt();
                byte[] hash = get_hash(password, salt);

                // creates the user
                executeUpdate(createUserStatement, userId, userHandle, name, email, salt, hash, verificationCode);

                commitTransaction();
                return new ResponseEntity<>(true, HttpStatus.OK);

            } catch (Exception e) {
                rollbackTransaction();

                if (!isDeadLock(e)) {
                    return new ResponseEntity<>(false, HttpStatus.INTERNAL_SERVER_ERROR);
                }
            }
        }
        return new ResponseEntity<>(false, HttpStatus.CONFLICT);
    }

    /**
     * Gets the user_id for a user_handle
     *
     * @effect tbl_users (R), non-locking
     * @return user_id / 200 status code if user_handle exists. otherwise, return null
     */
    public ResponseEntity<String> transaction_userHandleToUserId(String userHandle) {
        try{
            // checks that user handle is not mapped to a user id
            ResultSet resolveUserHandleToUserRecordRS = executeQuery(resolveUserHandleToUserRecordStatement,
                                                                     userHandle);
            if (!resolveUserHandleToUserRecordRS.next()) {
                resolveUserHandleToUserRecordRS.close();
                return new ResponseEntity<>(null, HttpStatus.GONE);
            }
            String userId = resolveUserHandleToUserRecordRS.getString("user_id");
            resolveUserHandleToUserRecordRS.close();

            return new ResponseEntity<>(userId, HttpStatus.OK);

        } catch (Exception e) {
            return new ResponseEntity<>(null, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * Gets the user_name for an email
     *
     * @effect tbl_users (R), non-locking
     * @return user_name / 200 status code if email exists. otherwise, return null
     */
    public ResponseEntity<String> transaction_resolveEmailToUserName(String email) {
        try {
            // retrieves the user record that the email is mapped to
            ResultSet resolveEmailToUserRecordRS = executeQuery(resolveEmailToUserRecordStatement, email);
            if (!resolveEmailToUserRecordRS.next()) {
                resolveEmailToUserRecordRS.close();
                return new ResponseEntity<>(null, HttpStatus.GONE);
            }
            String name = resolveEmailToUserRecordRS.getString("user_name");
            resolveEmailToUserRecordRS.close();

            return new ResponseEntity<>(name, HttpStatus.OK);

        } catch (Exception e) {
            return new ResponseEntity<>(null, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * Gets the user_id for an email
     *
     * @effect tbl_users (R), non-locking
     * @return user_id / 200 status code if email exists. otherwise, return null
     */
    public ResponseEntity<String> transaction_resolveEmailToUserId(String email) {
        try {
            // retrieves the user name that the email is mapped to
            ResultSet resolveEmailToUserRecordRS = executeQuery(resolveEmailToUserRecordStatement, email);
            if (!resolveEmailToUserRecordRS.next()) {
                resolveEmailToUserRecordRS.close();
                return new ResponseEntity<>(null, HttpStatus.GONE);
            }
            String userId = resolveEmailToUserRecordRS.getString("user_id");
            resolveEmailToUserRecordRS.close();

            return new ResponseEntity<>(userId, HttpStatus.OK);

        } catch (Exception e) {
            return new ResponseEntity<>(null, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * Gets the verification_code for an email
     *
     * @effect tbl_users (R), non-locking
     * @return verification_code / 200 status code if email exists. otherwise, return null
     */
    public ResponseEntity<String> transaction_resolveEmailToVerificationCode(String email) {
        try {
            // retrieves the user record that the email is mapped to
            ResultSet resolveEmailToUserRecordRS = executeQuery(resolveEmailToUserRecordStatement, email);
            if (!resolveEmailToUserRecordRS.next()) {
                return new ResponseEntity<>(null, HttpStatus.GONE);
            } else if (resolveEmailToUserRecordRS.getBoolean("has_verified_email")) {
                return new ResponseEntity<>(null, HttpStatus.BAD_REQUEST);
            }
            String verificationCode = resolveEmailToUserRecordRS.getString("verification_code");
            resolveEmailToUserRecordRS.close();

            return new ResponseEntity<>(verificationCode, HttpStatus.OK);

        } catch (Exception e) {
            return new ResponseEntity<>(null, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * Verifies whether the user's email has been verified
     *
     * @effect tbl_users (R), non-locking
     * @return true / 200 status code iff user has been verified
     */
    public ResponseEntity<Boolean> transaction_verifyEmail(String email) {
        try {
            // retrieves the verification code that the email is mapped to
            ResultSet checkEmailVerificationRS = executeQuery(checkEmailVerificationStatement, email);
            if (!checkEmailVerificationRS.next()) {
                checkEmailVerificationRS.close();
                return new ResponseEntity<>(false, HttpStatus.GONE);
            } else if (checkEmailVerificationRS.getBoolean("has_verified_email")) {
                return new ResponseEntity<>(true, HttpStatus.OK);
            } else {
                return new ResponseEntity<>(false, HttpStatus.BAD_REQUEST);
            }

        } catch (Exception e) {
            return new ResponseEntity<>(false, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * Processes the verification code
     *
     * @effect tbl_users (RW), acquires lock
     * @return true / 200 status code iff user is successfully verified
     */
    public ResponseEntity<Boolean> transaction_processVerificationCode(String verificationCode) {
        for (int attempts = 0; attempts < MAX_ATTEMPTS; attempts++) {
            try {
                beginTransaction();

                // checks whether the verification code has been used
                // this check is done to distinguish between expired vs used verification codes
                ResultSet checkVerificationCodeUsedRS = executeQuery(checkVerificationCodeUsedStatement,
                                                                     verificationCode);
                checkVerificationCodeUsedRS.next();
                if (checkVerificationCodeUsedRS.getBoolean("verification_code_used")) {
                    checkVerificationCodeUsedRS.close();
                    return new ResponseEntity<>(true, HttpStatus.BAD_REQUEST);
                }
                checkVerificationCodeUsedRS.close();

                // checks whether the verification code exists and is still active
                ResultSet checkVerificationCodeActiveRS = executeQuery(checkVerificationCodeActiveStatement,
                                                                       verificationCode);
                checkVerificationCodeActiveRS.next();
                if (!checkVerificationCodeActiveRS.getBoolean("verification_code_active")) {
                    checkVerificationCodeActiveRS.close();
                    return new ResponseEntity<>(false, HttpStatus.GONE);
                }
                checkVerificationCodeActiveRS.close();

                // verifies the user
                executeUpdate(updateEmailVerificationStatement, 1, verificationCode);

                commitTransaction();
                return new ResponseEntity<>(true, HttpStatus.OK);

            } catch (Exception e) {
                rollbackTransaction();

                if (!isDeadLock(e)) {
                    return new ResponseEntity<>(false, HttpStatus.INTERNAL_SERVER_ERROR);
                }
            }
        }
        return new ResponseEntity<>(false, HttpStatus.CONFLICT);
    }

    /**
     * Verifies the user's credentials
     *
     * @effect tbl_user (R), non-locking
     * @return true / 200 status code iff user's email and password matches
     */
    public ResponseEntity<Boolean> transaction_verifyCredentials(String email, String password) {
        try {
            // retrieves the user record that the email is mapped to
            ResultSet resolveEmailToUserRecordRS = executeQuery(resolveEmailToUserRecordStatement, email);
            if (!resolveEmailToUserRecordRS.next()) {
                // if user does not exist, vaguely claim that credentials are incorrect
                resolveEmailToUserRecordRS.close();
                return new ResponseEntity<>(false, HttpStatus.UNAUTHORIZED);
            }

            byte[] salt = resolveEmailToUserRecordRS.getBytes("salt");
            byte[] hash = resolveEmailToUserRecordRS.getBytes("hash");
            resolveEmailToUserRecordRS.close();

            if (Arrays.equals(hash, get_hash(password, salt))) {
                return new ResponseEntity<>(true, HttpStatus.OK);
            } else {
                return new ResponseEntity<>(false, HttpStatus.UNAUTHORIZED);
            }

        } catch (Exception e) {
            return new ResponseEntity<>(false, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * Updates the user's credentials
     *
     * @effect tbl_user (RW), acquires lock
     * @return true / 200 status code iff user's email and password have been successfully updated
     */
    public ResponseEntity<Boolean> transaction_updateCredentials(String userId, String password, String newPassword) {
        for (int attempts = 0; attempts < MAX_ATTEMPTS; attempts++) {
            try {
                beginTransaction();

                // retrieves the user record that the email is mapped to
                ResultSet resolveEmailToUserRecordRS = executeQuery(resolveUserIdToUserRecordStatement, userId);
                if (!resolveEmailToUserRecordRS.next()) {
                    // if user does not exist, vaguely claim that credentials are incorrect
                    resolveEmailToUserRecordRS.close();
                    return new ResponseEntity<>(false, HttpStatus.UNAUTHORIZED);
                }
                
                byte[] salt = resolveEmailToUserRecordRS.getBytes("salt");
                byte[] hash = resolveEmailToUserRecordRS.getBytes("hash");
                resolveEmailToUserRecordRS.close();

                // check that credentials are correct
                if (!Arrays.equals(hash, get_hash(password, salt))) {
                    return new ResponseEntity<>(false, HttpStatus.UNAUTHORIZED);
                }

                byte[] newSalt = get_salt();
                byte[] newHash = get_hash(newPassword, newSalt);

                // creates the user
                executeUpdate(updateCredentialsStatement, newSalt, newHash, userId);

                commitTransaction();
                return new ResponseEntity<>(true, HttpStatus.OK);

            } catch (Exception e) {
                rollbackTransaction();

                if (!isDeadLock(e)) {
                    return new ResponseEntity<>(false, HttpStatus.INTERNAL_SERVER_ERROR);
                }
            }
        }
        return new ResponseEntity<>(false, HttpStatus.CONFLICT);
    }

    /**
     * Verifies the supplied refresh token
     *
     * @effect tbl_user (R), non-locking
     * @return true / 200 status code iff refresh token is valid
     */
    public ResponseEntity<Boolean> transaction_verifyRefreshTokenId(String userId, String tokenId) {
        try {
            // retrieves the refresh token id that the user id is mapped to
            ResultSet resolveUserIdToUserRecordRS = executeQuery(resolveUserIdToUserRecordStatement, userId);
            if(!resolveUserIdToUserRecordRS.next()) {
                resolveUserIdToUserRecordRS.close();
                return new ResponseEntity<>(false, HttpStatus.UNAUTHORIZED);
            }

            String refreshTokenId = resolveUserIdToUserRecordRS.getString("refresh_token_id");
            resolveUserIdToUserRecordRS.close();

            if (tokenId.equals(refreshTokenId)) {
                return new ResponseEntity<>(true, HttpStatus.OK);
            } else {
                return new ResponseEntity<>(false, HttpStatus.UNAUTHORIZED);
            }

        } catch (Exception e) {
            return new ResponseEntity<>(false, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * Checks whether the supplied token family is current
     *
     * @effect tbl_user (R), non-locking
     * @return true / 200 status code iff token family is current
     */
    public ResponseEntity<Boolean> transaction_verifyRefreshTokenFamily(String userId, String tokenFamily) {
        try {
            // retrieves the refresh token family that the user id is mapped to
            ResultSet resolveUserIdToUserRecordRS = executeQuery(resolveUserIdToUserRecordStatement, userId);
            if(!resolveUserIdToUserRecordRS.next()) {
                resolveUserIdToUserRecordRS.close();
                return new ResponseEntity<>(false, HttpStatus.BAD_REQUEST);
            }

            String refreshTokenFamily = resolveUserIdToUserRecordRS.getString("refresh_token_family");
            resolveUserIdToUserRecordRS.close();

            if (tokenFamily.equals(refreshTokenFamily)) {
                return new ResponseEntity<>(true, HttpStatus.OK);
            } else {
                return new ResponseEntity<>(false, HttpStatus.BAD_REQUEST);
            }

        } catch (Exception e) {
            return new ResponseEntity<>(false, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * Updates the user's refresh token
     *
     * @effect tbl_user (W), non-locking
     * @return true / 200 status code iff refresh token has been successfully updated
     */
    public ResponseEntity<Boolean> transaction_updateRefreshToken(String userId, String refreshTokenId, String refreshTokenFamily) {
        try {
            executeUpdate(updateRefreshTokenStatement, refreshTokenId, refreshTokenFamily, userId);
            return new ResponseEntity<>(true, HttpStatus.OK);

        } catch (Exception e) {
            return new ResponseEntity<>(false, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * Updates the user's personal information
     *
     * @effect tbl_user (W), non-locking
     * @return true / 200 status iff user's personal information has been successfully updated
     */
    public ResponseEntity<Boolean> transaction_updatePersonalInformation(String userId, String userHandle, String name,
                                                                         String email, String dateOfBirth) {
        try {
            executeUpdate(updatePersonalInformationStatement, userHandle, name, email, dateOfBirth, userId);
            return new ResponseEntity<>(true, HttpStatus.OK);

        } catch (Exception e) {
            return new ResponseEntity<>(false, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * Updates the user's education information
     *
     * @effect tbl_user (W), non-locking
     * @return true / 200 status iff user's education information has been successfully updated
     */
    public ResponseEntity<Boolean> transaction_updateEducationInformation(String userId, String universityId, String major,
                                                                          String standing, String gpa) {
        try {
            executeUpdate(updateEducationInformationStatement, universityId, major, standing, gpa, userId);
            return new ResponseEntity<>(true, HttpStatus.OK);

        } catch (Exception e) {
            return new ResponseEntity<>(false, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * Updates the user's course registration information
     *
     * @effect tbl_courses (RW), tbl_registration (W), acquires lock
     * @return true / 200 status iff user's course registration information has been successfully updated
     */
    public ResponseEntity<Boolean> transaction_updateRegistrationInformation(String userId, String universityId, List<String> courses) {
        for (int attempts = 0; attempts < MAX_ATTEMPTS; attempts++) {
            try {
                beginTransaction();

                executeUpdate(deleteRegistrationStatement, userId);

                for (String courseId : courses) {

                    ResultSet resolveCourseIdUniversityIdToCourseRecordRS = executeQuery(resolveCourseIdUniversityIdToCourseRecordStatement,
                                                                                         courseId, universityId);
                    if (!resolveCourseIdUniversityIdToCourseRecordRS.next()) {
                        executeUpdate(createCourseStatement, courseId, universityId);
                    }
                    resolveCourseIdUniversityIdToCourseRecordRS.close();

                    executeUpdate(createRegistrationStatement, userId, courseId, universityId);
                }

                commitTransaction();
                return new ResponseEntity<>(true, HttpStatus.OK);

            } catch (Exception e) {
                rollbackTransaction();

                if (!isDeadLock(e)) {
                    return new ResponseEntity<>(false, HttpStatus.INTERNAL_SERVER_ERROR);
                }
            }
        }
        return new ResponseEntity<>(false, HttpStatus.CONFLICT);
    }

    /**
     * Updates the user's biography
     *
     * @effect tbl_user (W), non-locking
     * @return true / 200 status iff user's biography has been successfully updated
     */
    public ResponseEntity<Boolean> transaction_updateBiography(String userId, String biography) {
        try {
            executeUpdate(updateBiographyStatement, biography, userId);
            return new ResponseEntity<>(true, HttpStatus.OK);

        } catch (Exception e) {
            return new ResponseEntity<>(false, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * Updates the user's card color
     *
     * @effect tbl_user (W), non-locking
     * @return true / 200 status iff user's card color has been successfully updated
     */
    public ResponseEntity<Boolean> transaction_updateCardColor(String userId, String cardColor) {
        try {
            executeUpdate(updateCardColorStatement, cardColor, userId);
            return new ResponseEntity<>(true, HttpStatus.OK);

        } catch (Exception e) {
            return new ResponseEntity<>(false, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * Updates the user's media
     *
     * @effect tbl_media (W), acquires lock
     * @return true / 200 status iff user's media has been successfully updated
     */
    public ResponseEntity<Boolean> transaction_updateMedia(String userId, List<String> mediaUrls) {
        for (int attempts = 0; attempts < MAX_ATTEMPTS; attempts++) {
            try {
                beginTransaction();

                executeUpdate(deleteMediaStatement, userId);

                for (int i = 0; i < mediaUrls.size(); i++) {
                    String mediaUrl = mediaUrls.get(i);

                    executeUpdate(createMediaStatement, userId, i, mediaUrl);
                }

                commitTransaction();
                return new ResponseEntity<>(true, HttpStatus.OK);

            } catch (Exception e) {
                rollbackTransaction();

                if (!isDeadLock(e)) {
                    return new ResponseEntity<>(false, HttpStatus.INTERNAL_SERVER_ERROR);
                }
            }
        }
        return new ResponseEntity<>(false, HttpStatus.CONFLICT);
    }

    /**
     * Updates the user's profile picture
     *
     * @effect tbl_user (W), non-locking
     * @return true / 200 status iff user's profile picture has been successfully updated
     */
    public ResponseEntity<Boolean> transaction_updateProfilePicture(String userId, String profilePictureUrl) {
        try {
            executeUpdate(updateProfilePictureStatement, profilePictureUrl, userId);
            return new ResponseEntity<>(true, HttpStatus.OK);

        } catch (Exception e) {
            return new ResponseEntity<>(false, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * User rates friend
     *
     * @effect tbl_relationships (RW), acquires lock
     * @return true / 200 status iff successfully rated friend
     */
    public ResponseEntity<Boolean> transaction_rateUser(String userId, String friendUserId, int rating) {
        for (int attempts = 0; attempts < MAX_ATTEMPTS; attempts++) {
            try {
                beginTransaction();

                ResultSet resolveUserIdFriendUserIdToRecordRS = executeQuery(resolveUserIdFriendUserIdToRecordStatement, userId, friendUserId);
                if (!resolveUserIdFriendUserIdToRecordRS.next() || !resolveUserIdFriendUserIdToRecordRS.getString("relationship_status").equals("friends")) {
                    return new ResponseEntity<>(false, HttpStatus.BAD_REQUEST);
                }

                executeUpdate(updateRelationshipStatement, "friends", rating, userId, friendUserId);

                commitTransaction();
                return new ResponseEntity<>(true, HttpStatus.OK);

            } catch (Exception e) {
                rollbackTransaction();

                if (!isDeadLock(e)) {
                    return new ResponseEntity<>(false, HttpStatus.INTERNAL_SERVER_ERROR);
                }
            }
        }
        return new ResponseEntity<>(false, HttpStatus.CONFLICT);
    }

    /**
     * User likes other user
     *
     * @effect tbl_relationships (W), acquires lock
     * @return relationship_status / 200 status if successfully liked other user
     */
    public ResponseEntity<String> transaction_likeUser(String userId, String friendUserId) {
        // if friend likes user
        //      user and friend are friends
        //      friend and user are friends
        // else
        //      user and friend are liked
        throw new NotYetImplementedException();
    }

    public ResponseEntity<Boolean> transaction_dislikeUser() {
        throw new NotYetImplementedException();
    }

    public List<String> transaction_loadUsers() {
        throw new NotYetImplementedException();
    }

    public List<String> transaction_searchUsers() {
        throw new NotYetImplementedException();
    }

    /**
     * Starts transaction
     */
    private void beginTransaction() {
        try {
            conn.setAutoCommit(false);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Commits transaction. disabled when testEnabled and testSavepointEnabled are true
     */
    private void commitTransaction() {
        if (!(testEnabled && testSavepointEnabled)) {
            try {
                conn.commit();
                conn.setAutoCommit(true);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Rolls-back transaction. disabled when testEnabled and testSavepointEnabled are true
     */
    private void rollbackTransaction() {
        if (!(testEnabled && testSavepointEnabled)) {
            try {
                conn.rollback();
                conn.setAutoCommit(true);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Creates a savepoint. throws an IllegalStateException if testing is not enabled
     */
    public Savepoint createSavepoint() throws SQLException {
        if (testEnabled) {
            testSavepointEnabled = true;

            conn.setAutoCommit(false);
            return conn.setSavepoint("savepoint");

        } else {
            throw new IllegalStateException("Enable testing to create savepoints");
        }
    }

    /**
     * Reverts to a savepoint. throws an IllegalStateException if testing is not enabled
     */
    public void revertToSavepoint(Savepoint savepoint) throws SQLException {
        if (testEnabled) {
            testSavepointEnabled = false;

            conn.rollback(savepoint);
            conn.releaseSavepoint(savepoint);
            conn.commit();
            conn.setAutoCommit(true);

        } else {
            throw new IllegalStateException("Enable testing to revert to savepoints");
        }
    }

    /**
     * Checks whether the exception is caused by a transaction deadlock
     *
     * @return true iff the exception is caused by a transaction deadlock
     */
    private static boolean isDeadLock(Exception e) {
        if (e instanceof SQLException) {
            return ((SQLException) e).getErrorCode() == 1205;
        }
        return false;
    }

    /**
     * Sets the statement's parameters to the method's arguments in the order they are passed in
     *
     * @param statement canned SQL statement
     * @param args statement parameters
     */
    private void setParameters(PreparedStatement statement, Object... args) throws SQLException {
        int parameterIndex = 1;
        statement.clearParameters();
        for (Object arg : args) {
            if (arg == null) {
                statement.setNull(parameterIndex, Types.NULL);
            } else {
                statement.setObject(parameterIndex, arg);
            }
            parameterIndex++;
        }
    }

    /**
     * Executes the query statement with the specified parameters
     *
     * @param statement canned SQL statement
     * @param args statement parameters
     * @return query results as a ResultSet
     */
    private ResultSet executeQuery(PreparedStatement statement, Object... args) throws SQLException {
        setParameters(statement, args);
        return statement.executeQuery();
    }

    /**
     * Executes the update statement with the specified parameters
     *
     * @param statement canned SQL statement
     * @param args statement parameters
     */
    private void executeUpdate(PreparedStatement statement, Object... args) throws SQLException {
        setParameters(statement, args);
        statement.executeUpdate();
    }

    /**
     * Generates a random cryptographic salt
     *
     * @return cryptographic salt
     */
    private byte[] get_salt() {
        SecureRandom random = new SecureRandom();
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        return salt;
    }

    /**
     * Generates a cryptographic hash
     *
     * @param password password to be hashed
     * @param salt     salt for the has
     * @return cryptographic hash
     */
    private byte[] get_hash(String password, byte[] salt) {
        // Specify the hash parameters
        KeySpec spec = new PBEKeySpec(password.toCharArray(), salt, HASH_STRENGTH, KEY_LENGTH);

        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1");
            return factory.generateSecret(spec).getEncoded();

        } catch (NoSuchAlgorithmException | InvalidKeySpecException ex) {
            throw new IllegalStateException();
        }
    }
}
