export const testResults = [
  {
    id: 1,
    test: 'Complete Blood Count',
    date: 'Mar 5, 2026',
    status: 'New',
    provider: 'BF Health + Hospitals',
    details: {
      wbc: { value: '7.2', unit: '10^3/uL', range: '4.5-11.0', flag: 'Normal' },
      rbc: { value: '4.8', unit: '10^6/uL', range: '4.5-5.5', flag: 'Normal' },
      hemoglobin: { value: '14.2', unit: 'g/dL', range: '13.5-17.5', flag: 'Normal' },
      hematocrit: { value: '42.1', unit: '%', range: '38.0-50.0', flag: 'Normal' },
      platelets: { value: '250', unit: '10^3/uL', range: '150-400', flag: 'Normal' },
    },
  },
  {
    id: 2,
    test: 'Hemoglobin A1C',
    date: 'Feb 28, 2026',
    status: 'Reviewed',
    provider: 'Kings County Lab',
    details: {
      a1c: { value: '6.8', unit: '%', range: '<7.0', flag: 'Normal' },
      estimatedGlucose: { value: '148', unit: 'mg/dL', range: '—', flag: 'Normal' },
    },
  },
  {
    id: 3,
    test: 'Comprehensive Metabolic Panel',
    date: 'Feb 15, 2026',
    status: 'Reviewed',
    provider: 'BF Health + Hospitals',
    details: {
      glucose: { value: '105', unit: 'mg/dL', range: '70-100', flag: 'High' },
      creatinine: { value: '0.9', unit: 'mg/dL', range: '0.7-1.3', flag: 'Normal' },
      sodium: { value: '140', unit: 'mEq/L', range: '136-145', flag: 'Normal' },
      potassium: { value: '4.2', unit: 'mEq/L', range: '3.5-5.0', flag: 'Normal' },
    },
  },
]

