println(pdfFile.getAbsolutePath()+
"\t"+bundle.id+"\t"+
bundle.identifier.value+"\t"+
accessCode+"\t"+
medication.code.coding[0].code+"\t"+
medication.code.text+"\t"+
coverage.payor[0].identifier.value+"\t"+
coverage.payor[0].display);