export * from './generated-new-system/new-system';
export { ApiError, configureGateway, getSession, refreshSession, clearSession } from './gateway';
// Short identity names remain direct aliases of contract-generated operations.
export {
  iamLoginPostApiV1AuthAdminLogin as loginAdmin,
  iamLoginPostApiV1AuthOtpLogin as loginWithOtp,
  iamLoginPostApiV1AuthPhoneLogin as loginWithPhone,
  iamSendPostApiV1AuthOtpSend as sendOtp,
  iamLogoutPostApiV1AuthLogout as logout
} from './generated-new-system/new-system';
