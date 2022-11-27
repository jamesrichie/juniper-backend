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

public class DatabaseConnection {

    // Database connection
    private final Connection conn;

    // Flag enabling the use of testing features
    private Boolean testEnabled;

    // Flag enabling the creation of savepoints
    private Boolean testSavepointEnabled;

    // Password hashing parameter constants
    private static final int HASH_STRENGTH = 65536;
    private static final int KEY_LENGTH = 128;

    // Number of attempts when encountering deadlock
    private static final int MAX_ATTEMPTS = 16;

    // Creates a user
    private static final String CREATE_USER = "INSERT INTO tbl_users VALUES (?, ?, ?, ?, ?, ?, null, null, null, ?, UTC_TIMESTAMP(), false, false, null, null, null, null, null, 0, 0, null, null, null, null, null)";
    private PreparedStatement createUserStatement;

    // Checks if verification code has been used
    // if false, then either code is still active, or has expired
    private static final String CHECK_VERIFICATION_CODE_USED = "SELECT EXISTS(SELECT * FROM tbl_users WHERE verification_code = ? AND has_verified_email = 1) AS verification_code_used";
    private PreparedStatement checkVerificationCodeUsedStatement;

    // Checks if verification code is active
    // if false, then either code has been used, or has expired
    private static final String CHECK_VERIFICATION_CODE_ACTIVE = "SELECT EXISTS(SELECT * FROM tbl_users WHERE verification_code = ? AND has_verified_email = 0) AS verification_code_active";
    private PreparedStatement checkVerificationCodeActiveStatement;

    // Checks if the email has been verified
    private static final String CHECK_EMAIL_VERIFICATION = "SELECT has_verified_email FROM tbl_users WHERE email = ?";
    private PreparedStatement checkEmailVerificationStatement;

    // Sets a user's has_verified_email field
    private static final String UPDATE_EMAIL_VERIFICATION = "UPDATE tbl_users SET has_verified_email = ? WHERE verification_code = ?";
    private PreparedStatement updateEmailVerificationStatement;

    // Sets a user's refresh_token_id and refresh_token_family fields
    private static final String UPDATE_REFRESH_TOKEN = "UPDATE tbl_users SET refresh_token_id = ?, refresh_token_family = ? WHERE user_id = ?";
    private PreparedStatement updateRefreshTokenStatement;

    // Sets a user's email, salt and hash fields
    private static final String UPDATE_CREDENTIALS = "UPDATE tbl_users SET email = ?, salt = ?, hash = ? WHERE user_id = ?";
    private PreparedStatement updateCredentialsStatement;

    // Maps user handle -> user record
    private static final String RESOLVE_USER_HANDLE_TO_USER_RECORD = "SELECT * FROM tbl_users WHERE user_handle = ?";
    private PreparedStatement resolveUserHandleToUserRecordStatement;

    // Maps email -> user record
    private static final String RESOLVE_EMAIL_TO_USER_RECORD = "SELECT * FROM tbl_users WHERE email = ?";
    private PreparedStatement resolveEmailToUserRecordStatement;

    // Maps user id -> user record
    private static final String RESOLVE_USER_ID_TO_USER_RECORD = "SELECT * FROM tbl_users WHERE user_id = ?";
    private PreparedStatement resolveUserIdToUserRecordStatement;

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

        String connectionUrl = String.format("jdbc:mysql://%s:%s/%s?user=%s&password=%s", endpoint, port, dbName, adminName, password);
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
        createUserStatement = conn.prepareStatement(CREATE_USER);
        checkVerificationCodeUsedStatement = conn.prepareStatement(CHECK_VERIFICATION_CODE_USED);
        checkVerificationCodeActiveStatement = conn.prepareStatement(CHECK_VERIFICATION_CODE_ACTIVE);
        updateEmailVerificationStatement = conn.prepareStatement(UPDATE_EMAIL_VERIFICATION);
        updateRefreshTokenStatement = conn.prepareStatement(UPDATE_REFRESH_TOKEN);
        updateCredentialsStatement = conn.prepareStatement(UPDATE_CREDENTIALS);
        resolveUserHandleToUserRecordStatement = conn.prepareStatement(RESOLVE_USER_HANDLE_TO_USER_RECORD);
        resolveEmailToUserRecordStatement = conn.prepareStatement(RESOLVE_EMAIL_TO_USER_RECORD);
        resolveUserIdToUserRecordStatement = conn.prepareStatement(RESOLVE_USER_ID_TO_USER_RECORD);
        checkEmailVerificationStatement = conn.prepareStatement(CHECK_EMAIL_VERIFICATION);
    }

    /**
     * Gets the user id for a user handle
     *
     * @return if user handle exists, return user id. otherwise return null
     */
    public ResponseEntity<String> transaction_userHandleToUserIdResolution(String userHandle) {
        try{
            // checks that user handle is not mapped to a user id
            ResultSet resolveUserHandleToUserRecordRS = executeQuery(resolveUserHandleToUserRecordStatement, userHandle);
            if (!resolveUserHandleToUserRecordRS.next()) {
                resolveUserHandleToUserRecordRS.close();
                return new ResponseEntity<>(null, HttpStatus.OK);
            }
            String userId = resolveUserHandleToUserRecordRS.getString("user_id");
            resolveUserHandleToUserRecordRS.close();

            return new ResponseEntity<>(userId, HttpStatus.OK);

        } catch (Exception e) {
            return new ResponseEntity<>(null, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * Creates a new user with an unverified email
     *
     * @return true iff successfully created new user
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
                if (transaction_userHandleToUserIdResolution(userHandle).getBody() != null) {
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
     * Gets the name for a specified user
     *
     * @return if email exists, return name. otherwise return null
     */
    public ResponseEntity<String> transaction_getUserName(String email) {
        try {
            // retrieves the user record that the email is mapped to
            ResultSet resolveEmailToUserRecordRS = executeQuery(resolveEmailToUserRecordStatement, email);
            if (!resolveEmailToUserRecordRS.next()) {
                resolveEmailToUserRecordRS.close();
                return new ResponseEntity<>(null, HttpStatus.BAD_REQUEST);
            }
            String name = resolveEmailToUserRecordRS.getString("user_name");
            resolveEmailToUserRecordRS.close();

            return new ResponseEntity<>(name, HttpStatus.OK);

        } catch (Exception e) {
            return new ResponseEntity<>(null, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * Gets the id for a specified user
     *
     * @return if email exists, return id. otherwise return null
     */
    public ResponseEntity<String> transaction_getUserId(String email) {
        try {
            // retrieves the username that the email is mapped to
            ResultSet resolveEmailToUserRecordRS = executeQuery(resolveEmailToUserRecordStatement, email);
            if (!resolveEmailToUserRecordRS.next()) {
                resolveEmailToUserRecordRS.close();
                return new ResponseEntity<>(null, HttpStatus.BAD_REQUEST);
            }
            String name = resolveEmailToUserRecordRS.getString("user_id");
            resolveEmailToUserRecordRS.close();

            return new ResponseEntity<>(name, HttpStatus.OK);

        } catch (Exception e) {
            return new ResponseEntity<>(null, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }



    /**
     * Gets the verification code for a specified user
     *
     * @return if account exists, return verification code. otherwise return null
     */
    public ResponseEntity<String> transaction_getVerificationCode(String email) {
        try {
            // gets the verification code associated with the email
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
     * Checks whether the user has been verified
     *
     * @return
     */
    public ResponseEntity<Boolean> transaction_checkEmailVerification(String email) {
        try {
            // gets the verification code associated with the email
            ResultSet checkEmailVerificationRS = executeQuery(checkEmailVerificationStatement, email);
            checkEmailVerificationRS.next();

            return new ResponseEntity<>(checkEmailVerificationRS.getBoolean("has_verified_email"), HttpStatus.OK);

        } catch (Exception e) {
            return new ResponseEntity<>(null, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * Verifies the user associated with the verification code
     *
     * @return true iff user has been verified
     */
    public ResponseEntity<Boolean> transaction_verifyEmail(String verificationCode) {
        for (int attempts = 0; attempts < MAX_ATTEMPTS; attempts++) {
            try {
                beginTransaction();

                // checks whether the verification code has been used
                // this check is done to distinguish between expired vs used verification codes
                ResultSet checkVerificationCodeUsedRS = executeQuery(checkVerificationCodeUsedStatement, verificationCode);
                checkVerificationCodeUsedRS.next();
                if (checkVerificationCodeUsedRS.getBoolean("verification_code_used")) {
                    checkVerificationCodeUsedRS.close();
                    return new ResponseEntity<>(true, HttpStatus.BAD_REQUEST);
                }
                checkVerificationCodeUsedRS.close();

                // checks whether the verification code exists and is still active
                ResultSet checkVerificationCodeActiveRS = executeQuery(checkVerificationCodeActiveStatement, verificationCode);
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
     * @return true iff user's email and password matches
     */
    public ResponseEntity<Boolean> transaction_verifyCredentials(String email, String password) {
        try {
            // retrieves the user record that the email is mapped to
            ResultSet resolveEmailToUserRecordRS = executeQuery(resolveEmailToUserRecordStatement, email);
            if (!resolveEmailToUserRecordRS.next()) {
                // if user does not exist, vaguely claim that credentials are incorrect
                resolveEmailToUserRecordRS.close();
                return new ResponseEntity<>(false, HttpStatus.OK);
            }

            byte[] salt = resolveEmailToUserRecordRS.getBytes("salt");
            byte[] hash = resolveEmailToUserRecordRS.getBytes("hash");
            resolveEmailToUserRecordRS.close();

            return new ResponseEntity<>(Arrays.equals(hash, get_hash(password, salt)), HttpStatus.OK);

        } catch (Exception e) {
            return new ResponseEntity<>(null, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * Verifies the user's credentials
     *
     * @return true iff user's email and password matches
     */
    public ResponseEntity<Boolean> transaction_updateCredentials(String email, String password, String newEmail, String newPassword) {
        for (int attempts = 0; attempts < MAX_ATTEMPTS; attempts++) {
            try {
                beginTransaction();

                // retrieves the user record that the email is mapped to
                ResultSet resolveEmailToUserRecordRS = executeQuery(resolveEmailToUserRecordStatement, email);
                if (!resolveEmailToUserRecordRS.next()) {
                    // if user does not exist, vaguely claim that credentials are incorrect
                    resolveEmailToUserRecordRS.close();
                    return new ResponseEntity<>(false, HttpStatus.OK);
                }

                String userId = resolveEmailToUserRecordRS.getString("userId");
                byte[] salt = resolveEmailToUserRecordRS.getBytes("salt");
                byte[] hash = resolveEmailToUserRecordRS.getBytes("hash");
                resolveEmailToUserRecordRS.close();

                // check that credentials are correct
                if (!Arrays.equals(hash, get_hash(password, salt))) {
                    return new ResponseEntity<>(false, HttpStatus.OK);
                }

                byte[] newSalt = get_salt();
                byte[] newHash = get_hash(newPassword, newSalt);

                // creates the user
                executeUpdate(updateCredentialsStatement, newEmail, newSalt, newHash, userId);

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

    public ResponseEntity<Boolean> transaction_verifyRefreshTokenId(String userId, String tokenId) {
        try {
            // retrieves the refresh token id that the user id is mapped to
            ResultSet resolveUserIdToUserRecordRS = executeQuery(resolveUserIdToUserRecordStatement, userId);
            if(!resolveUserIdToUserRecordRS.next()) {
                resolveUserIdToUserRecordRS.close();
                return new ResponseEntity<>(null, HttpStatus.BAD_REQUEST);
            }

            String refreshTokenId = resolveUserIdToUserRecordRS.getString("refresh_token_id");
            resolveUserIdToUserRecordRS.close();

            return new ResponseEntity<>(tokenId.equals(refreshTokenId), HttpStatus.OK);

        } catch (Exception e) {
            return new ResponseEntity<>(null, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    public ResponseEntity<Boolean> transaction_verifyRefreshTokenFamily(String userId, String tokenFamily) {
        try {
            // retrieves the refresh token family that the user id is mapped to
            ResultSet resolveUserIdToUserRecordRS = executeQuery(resolveUserIdToUserRecordStatement, userId);
            if(!resolveUserIdToUserRecordRS.next()) {
                resolveUserIdToUserRecordRS.close();
                return new ResponseEntity<>(null, HttpStatus.BAD_REQUEST);
            }

            String refreshTokenFamily = resolveUserIdToUserRecordRS.getString("refresh_token_family");
            resolveUserIdToUserRecordRS.close();

            return new ResponseEntity<>(tokenFamily.equals(refreshTokenFamily), HttpStatus.OK);

        } catch (Exception e) {
            return new ResponseEntity<>(null, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
    
    public ResponseEntity<Boolean> transaction_updateRefreshToken(String userId, String refreshTokenId, String refreshTokenFamily) {
        for (int attempts = 0; attempts < MAX_ATTEMPTS; attempts++) {
            try {
                beginTransaction();

                executeUpdate(updateRefreshTokenStatement, refreshTokenId, refreshTokenFamily, userId);

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

    public Boolean transaction_updateUserProfile() {
        throw new NotYetImplementedException();
    }

    public List<String> transaction_loadUsers() {
        throw new NotYetImplementedException();
    }

    public Boolean transaction_rateUser() {
        throw new NotYetImplementedException();
    }

    public Boolean transaction_likeUser() {
        throw new NotYetImplementedException();
    }

    public Boolean transaction_dislikeUser() {
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
     * Sets the query's parameters to the method's arguments in the order they are passed in
     *
     * @param statement canned SQL query
     * @param args query parameters
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
     * @param statement canned SQL query
     * @param args query parameters
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
     * @param args query parameters
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
