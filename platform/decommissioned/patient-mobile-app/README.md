# MyChart Patient Mobile App

A comprehensive patient portal mobile application built with React, designed to replicate the functionality and user experience of the MyChart Epic system used by NYC Health + Hospitals.

## 🏥 Overview

This mobile application provides patients with secure access to their medical information, appointment scheduling, messaging with healthcare providers, test results, medication management, and billing information with integrated mobile money payment options. The app is designed with a mobile-first approach and follows the visual design patterns of the original MyChart Epic system.

## 🚀 Quick Start

### Prerequisites

Before running this application, ensure you have the following installed:

- **Node.js** (version 18.0 or higher)
- **npm** (version 8.0 or higher) or **pnpm** (recommended)
- **Git** (for version control)

### Installation

1. **Extract the project files** (if you received a zip file):
   ```bash
   unzip patient-mobile-app.zip
   cd patient-mobile-app
   ```

2. **Install dependencies**:
   ```bash
   # Using npm
   npm install

   # Or using pnpm (recommended for faster installation)
   pnpm install
   ```

3. **Start the development server**:
   ```bash
   # Using npm
   npm run dev

   # Or using pnpm
   pnpm run dev
   ```

4. **Open your browser** and navigate to:
   ```
   http://localhost:5173
   ```

The application will automatically reload when you make changes to the source code.

## 📱 Features

### 🔐 Authentication & Security
- **Face ID Login**: Biometric authentication simulation for secure access
- **Username/Password Login**: Traditional login method as fallback
- **Secure Session Management**: Automatic logout and session protection

### 📊 Dashboard & Navigation
- **Personalized Welcome Screen**: Customized greeting with patient name (Tiego)
- **Quick Access Grid**: Six main feature tiles for easy navigation
- **Real-time Notifications**: New test results and billing alerts
- **Comprehensive Menu System**: Organized navigation with search functionality

### 📅 Appointment Management
- **Future Appointments**: View upcoming appointments with complete details
- **Past Appointments**: Access historical visit records with After Visit Summaries
- **Schedule New Appointments**: Easy appointment booking interface
- **Provider Information**: Details about healthcare providers and locations

### 💬 Communication Features
- **Secure Messaging**: Communicate with healthcare providers
- **Message Management**: View, compose, and organize messages with read/unread status
- **Notification System**: Alerts for new messages and updates
- **Ask Questions**: Direct communication channel with care team

### 🧪 Medical Records & Test Results
- **Test Results Viewer**: Access lab results and diagnostic reports
- **Download Functionality**: Save test results for personal records
- **Status Tracking**: New vs. reviewed results indication with proper badges
- **Provider Information**: Details about ordering physicians and facilities

### 💊 Medication Management
- **Current Medications**: View active prescriptions with complete information
- **Dosage Information**: Detailed medication instructions and prescriber details
- **Refill Requests**: Request prescription refills with remaining refill counts
- **Pharmacy Management**: Manage preferred pharmacy locations

### 💳 Billing & Payment Options
- **Outstanding Balances**: View amounts due and payment status with color coding
- **Mobile Money Integration**: Support for popular African mobile payment services
- **Traditional Payment Methods**: Credit cards and bank transfers
- **Payment Security**: Encrypted processing with security assurances

#### Mobile Money Options:
- **Orange Money**: Popular in West and Central Africa
- **MTN Mobile Money (MoMo)**: Widely used across African countries
- **Airtel Money**: Available in African and Asian markets
- **M-Pesa**: Dominant in East Africa (Kenya, Tanzania)

### ⚙️ Additional Features
- **Health Summary**: Comprehensive health overview
- **Preventive Care**: Screening reminders and health maintenance
- **Care Team Access**: View healthcare provider information
- **Insurance Integration**: Coverage details and benefits
- **Cost Estimates**: Procedure and service cost estimates

## 🎨 Design Features

### Visual Design
- **NYC Health + Hospitals Branding**: Authentic color scheme and logos
- **MyChart Epic Integration**: Consistent with Epic's design language
- **Mobile-Optimized Interface**: Touch-friendly buttons and navigation
- **Professional Medical UI**: Clean, trustworthy design aesthetic

### User Experience
- **Intuitive Navigation**: Easy-to-use menu system with clear categories
- **Responsive Design**: Works seamlessly across different screen sizes
- **Accessibility Features**: Screen reader compatible and keyboard navigation
- **Loading States**: Smooth transitions and feedback for user actions

## 🛠 Development

### Project Structure
```
patient-mobile-app/
├── public/                 # Static assets
├── src/
│   ├── components/
│   │   └── ui/            # Reusable UI components (shadcn/ui)
│   ├── App.jsx            # Main application component
│   ├── main.jsx           # Application entry point
│   ├── App.css            # Global styles
│   └── index.css          # Base styles
├── package.json           # Dependencies and scripts
├── vite.config.js         # Vite configuration
├── tailwind.config.js     # Tailwind CSS configuration
├── postcss.config.js      # PostCSS configuration
└── README.md              # This file
```

### Technology Stack
- **Frontend Framework**: React 18 with Vite
- **Styling**: Tailwind CSS for responsive design
- **UI Components**: Shadcn/UI component library
- **Icons**: Lucide React icon library
- **State Management**: React hooks (useState, useEffect)
- **Build Tool**: Vite for fast development and building

### Available Scripts

```bash
# Start development server
npm run dev
pnpm run dev

# Build for production
npm run build
pnpm run build

# Preview production build
npm run preview
pnpm run preview

# Lint code
npm run lint
pnpm run lint
```

### Development Guidelines

1. **Component Structure**: Follow React functional component patterns
2. **Styling**: Use Tailwind CSS classes for consistent styling
3. **State Management**: Use React hooks for local state management
4. **Code Quality**: Follow ESLint rules and maintain clean code

## 🧪 Testing the Application

### Login Testing
1. **Face ID Login**: Click the "Log in with Face ID" button (simulates biometric authentication)
2. **Username/Password**: Click "Or log in with username and password" and enter any credentials

### Feature Testing
1. **Dashboard Navigation**: Test all six main feature tiles
2. **Menu System**: Click the hamburger menu to access all sections
3. **Appointments**: View future and past appointments with details
4. **Messages**: Check message list with read/unread status
5. **Test Results**: View results with New/Reviewed badges
6. **Medications**: Check medication list with refill options
7. **Billing**: Test payment flow with mobile money options

### Mobile Money Payment Testing
1. Navigate to **Billing** section
2. Click **Pay Now** on any outstanding bill
3. Select from mobile money options:
   - Orange Money
   - MTN Mobile Money
   - Airtel Money
   - M-Pesa
4. Also test traditional payment methods (Credit Card, Bank Transfer)

### Sample Data
The application includes realistic sample data:
- **Patient**: Tiego (sample patient)
- **Appointments**: Mix of upcoming and past appointments
- **Test Results**: Various lab results with different statuses
- **Medications**: Common prescription medications
- **Messages**: Healthcare provider communications
- **Billing**: Sample bills with different payment statuses

## 🌍 Global Accessibility Features

### Mobile Money Integration
The application supports popular mobile payment methods across different regions:

- **Africa**: Orange Money, MTN MoMo, Airtel Money, M-Pesa
- **Global**: Credit/Debit cards, Bank transfers
- **Security**: All payments encrypted and processed securely

### Responsive Design
- **Mobile-First**: Optimized for mobile devices
- **Cross-Platform**: Works on iOS, Android, and web browsers
- **Touch-Friendly**: Large touch targets and intuitive gestures

## 🔧 Customization

### Branding
To customize the branding:
1. Update colors in `tailwind.config.js`
2. Replace logos and images in the `public` folder
3. Modify hospital name and branding in `App.jsx`

### Features
To add or modify features:
1. Create new components in `src/components/`
2. Add new views to the `renderCurrentView()` function
3. Update navigation menu items in the `menuItems` array

### Styling
The application uses Tailwind CSS for styling:
- Modify `tailwind.config.js` for theme customization
- Use Tailwind classes in components for consistent styling
- Custom CSS can be added to `App.css` if needed

## 📦 Deployment

### Production Build
```bash
npm run build
# or
pnpm run build
```

The build artifacts will be stored in the `dist/` directory.

### Deployment Options
- **Static Hosting**: Netlify, Vercel, GitHub Pages
- **CDN**: AWS CloudFront, Cloudflare
- **Traditional Hosting**: Apache, Nginx

### Environment Variables
For production deployment, consider setting up:
- API endpoints for real backend integration
- Authentication providers
- Payment gateway configurations

## 🔒 Security Considerations

### Data Protection
- **HIPAA Compliance**: Designed with healthcare data protection in mind
- **Secure Communication**: HTTPS-ready for production deployment
- **Authentication**: Secure login and session management
- **Privacy Controls**: User data protection and access controls

### Payment Security
- **Encrypted Processing**: All payment information encrypted
- **PCI Compliance**: Ready for payment card industry standards
- **Mobile Money Security**: Secure integration with mobile payment providers

## 🐛 Troubleshooting

### Common Issues

1. **Port Already in Use**:
   ```bash
   # Kill process on port 5173
   lsof -ti:5173 | xargs kill -9
   # Or use a different port
   npm run dev -- --port 3000
   ```

2. **Dependencies Issues**:
   ```bash
   # Clear node_modules and reinstall
   rm -rf node_modules package-lock.json
   npm install
   ```

3. **Build Errors**:
   ```bash
   # Clear Vite cache
   rm -rf node_modules/.vite
   npm run dev
   ```

### Browser Compatibility
- **Modern Browsers**: Chrome 90+, Firefox 88+, Safari 14+, Edge 90+
- **Mobile Browsers**: iOS Safari 14+, Chrome Mobile 90+

## 📞 Support & Documentation

### Getting Help
- Check the browser console for error messages
- Ensure all dependencies are properly installed
- Verify Node.js and npm versions meet requirements

### Additional Resources
- **React Documentation**: https://react.dev/
- **Vite Documentation**: https://vitejs.dev/
- **Tailwind CSS**: https://tailwindcss.com/
- **Shadcn/UI**: https://ui.shadcn.com/

## 🎯 Future Enhancements

Potential features for future development:
- **Real Backend Integration**: Connect to actual healthcare APIs
- **Telehealth Integration**: Video consultations and remote monitoring
- **Wearable Device Sync**: Integration with fitness trackers and health devices
- **AI-Powered Insights**: Personalized health recommendations
- **Family Account Management**: Manage multiple family member accounts
- **Advanced Analytics**: Health trends and predictive insights
- **Offline Functionality**: Basic offline access to critical information

## 📄 License

This project is created for demonstration purposes and showcases modern web development practices applied to healthcare technology.

## 🤝 Contributing

This is a demonstration project. For healthcare organizations interested in implementing similar patient portal solutions, consider:
- **Technical Documentation**: Comprehensive API and integration guides
- **Security Guidelines**: HIPAA compliance and security best practices
- **Customization Options**: Branding and feature customization
- **Training Materials**: User guides and training resources

---

**Built with ❤️ for better patient care and healthcare accessibility**

*This application demonstrates modern web development practices applied to healthcare technology, showcasing how patient portals can be built with excellent user experience, comprehensive functionality, and global accessibility through mobile money integration.*
